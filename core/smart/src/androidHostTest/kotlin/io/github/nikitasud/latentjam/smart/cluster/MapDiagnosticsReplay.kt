/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.Genres
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Opt-in diagnostic for the Map tab. Nothing runs unless a directory is supplied:
 *
 *  - `MAP_DIAG_DIR=<dir>`   — `smart_index_vecs.npy` + `smart_text_vecs.npy` + `*_ids.txt`
 *                             (a device dump with both modalities), optional `map_layout.tsv`
 *                             (the 2D layout the device currently draws) and `mediastore.txt`
 *                             (titles/artists/albums/genres for naming).
 *  - `MAP_DIAG_BENCH=<dir>` — `audio.f32` + `meta.tsv` + `ids.txt` (a bench fixture).
 *
 * Run: `./gradlew :core:smart:testAndroidHostTest --tests "*MapDiagnosticsReplay*" --rerun-tasks`.
 *
 * Metrics are deliberately scale-aware: 2D distances are plain Euclidean in the 0..1 square (no
 * re-normalization, which would throw the radius away), neighbour agreement is reported against a
 * random-labelling baseline, and cohesion is compared across modalities only after subtracting the
 * space's own mean random-pair cosine.
 */
class MapDiagnosticsReplay {
    @Test
    fun `device dump`() {
        val dir = System.getenv("MAP_DIAG_DIR") ?: return
        val audio = readNpy(File(dir, "smart_index_vecs.npy"))
        val text = readNpy(File(dir, "smart_text_vecs.npy"))
        val audioIds = File(dir, "smart_index_ids.txt").readLines().filter { it.isNotBlank() }
        val textIds = File(dir, "smart_text_ids.txt").readLines().filter { it.isNotBlank() }
        val meta = readMetadata(File(dir, "mediastore.txt"))
        val audioMap = audioIds.mapIndexedNotNull { i, id -> audio.rows.getOrNull(i)?.let { TrackId(id) to it } }.toMap()
        val textMap = textIds.mapIndexedNotNull { i, id -> text.rows.getOrNull(i)?.let { TrackId(id) to it } }.toMap()
        val ids = (audioIds + textIds).distinct().map(::TrackId)
        println("DIAG dump: audio=${audio.rows.size}x${audio.dim} text=${text.rows.size}x${text.dim} meta=${meta.size}")
        val library = ids.map { id ->
            val facts = meta[id.value]
            TrackDescriptor(
                id = id,
                title = facts?.get(1),
                artist = facts?.get(2),
                album = facts?.get(3),
                genre = facts?.get(4),
                year = facts?.get(5)?.toIntOrNull(),
            )
        }
        val modes = listOf(
            "fused(0.75 metadata)" to LibraryVectorFusion.build(ids, audioMap, textMap, audio.dim, text.dim),
            "audio only" to LibraryVectorFusion.build(
                ids, audioMap, emptyMap(), audio.dim, text.dim, minHybridCoverage = 0f,
            ),
            "metadata only" to LibraryVectorFusion.build(
                ids, emptyMap(), textMap, audio.dim, text.dim, minHybridCoverage = 0f,
            ),
        )
        val vectorsByMode = HashMap<String, Map<TrackId, FloatArray>>()
        for ((label, spaceNullable) in modes) {
            val space = spaceNullable ?: continue
            // The row matrix is one-shot: take the vectors before anything consumes the space.
            val vectors = vectorsOf(space)
            vectorsByMode[label] = vectors
            report(label, space, vectors, library)
        }
        System.getenv("MAP_DIAG_FOCUS")?.let { needle ->
            val space = modes.first().second ?: return@let
            val vectors = vectorsByMode.getValue("fused(0.75 metadata)")
            val worlds = LibraryWorlds.discover(library, vectors, space.dim)
            val albumGroups = library.filter { !it.album.isNullOrBlank() }.groupBy { it.album.orEmpty() }
                .filterValues { it.size > 1 }
                .map { (album, tracks) -> album to tracks.mapTo(HashSet()) { it.id } }
            val playlists = readPlaylists(File(dir, "playlists.tsv"))
            val named = LibraryWorlds.namedAfterGroups(worlds, playlists + albumGroups)
            println("DIAG именование: плейлистов=${playlists.size} альбомов=${albumGroups.size}")
            playlists.forEach { (name, members) ->
                val best = named.indices.maxByOrNull { index ->
                    named[index].tracks.count { it.id in members }.toDouble() / named[index].tracks.size.coerceAtLeast(1)
                } ?: return@forEach
                val containment = named[best].tracks.count { it.id in members }.toDouble() / named[best].tracks.size
                val specificity = named[best].tracks.count { it.id in members }.toDouble() / members.size
                if (containment >= 0.3) {
                    println(
                        "DIAG   плейлист \"${name.take(20)}\" (${members.size}) лучший регион[$best] " +
                            "name=${named[best].name.take(28)} containment=${f(containment)} specificity=${f(specificity)}",
                    )
                }
            }
            // Every claim on the region that holds the focused tracks: who wanted the name and who won.
            val focusWorld = named.indices.maxByOrNull { index ->
                named[index].tracks.count { track ->
                    val facts = library.firstOrNull { it.id == track.id }
                    facts?.album.orEmpty().contains(needle, ignoreCase = true) ||
                        facts?.genre.orEmpty().contains(needle, ignoreCase = true) ||
                        facts?.title.orEmpty().contains(needle, ignoreCase = true)
                }
            }
            if (focusWorld != null) {
                val world = named[focusWorld]
                println("DIAG регион[$focusWorld] '${world.name}' — все заявки (containment=доля региона, specificity=доля группы):")
                (playlists + albumGroups).forEach { (groupName, members) ->
                    val inside = world.tracks.count { it.id in members }
                    val containment = inside.toDouble() / world.tracks.size
                    if (containment >= 0.4) {
                        println(
                            "DIAG   '${groupName.take(34)}' группа=${members.size} внутри=$inside " +
                                "containment=${f(containment)} specificity=${f(inside.toDouble() / members.size)}" +
                                if (groupName.equals(world.name, ignoreCase = true)) "  <-- ВЫИГРАЛА" else "",
                        )
                    }
                }
            }

            // What the names would be if the tighter claim won instead of the broadest one. Nothing
            // here changes the product; it only shows the owner what the alternative rule reads like.
            run {
                data class AltClaim(val world: Int, val name: String, val f1: Double)
                val claims = ArrayList<AltClaim>()
                named.forEachIndexed { index, world ->
                    (playlists + albumGroups).forEach { (groupName, members) ->
                        if (groupName.isBlank() || members.isEmpty()) return@forEach
                        val inside = world.tracks.count { it.id in members }
                        val containment = inside.toDouble() / world.tracks.size
                        val specificity = inside.toDouble() / members.size
                        if (containment >= 0.45 && inside > 0) {
                            claims += AltClaim(index, groupName, 2 * containment * specificity / (containment + specificity))
                        }
                    }
                }
                val takenWorlds = HashSet<Int>()
                val takenNames = HashSet<String>()
                val alt = HashMap<Int, String>()
                for (claim in claims.sortedByDescending { it.f1 }) {
                    if (claim.world in takenWorlds || claim.name.lowercase() in takenNames) continue
                    takenWorlds += claim.world
                    takenNames += claim.name.lowercase()
                    alt[claim.world] = claim.name
                }
                println("DIAG имена: сейчас / если побеждает точная заявка (F1):")
                named.indices.forEach { index ->
                    val now = named[index].name
                    val alternative = alt[index] ?: "(без имени группы)"
                    if (now != alternative) {
                        println("DIAG   регион[$index] size=${named[index].tracks.size}: '$now' -> '$alternative'")
                    }
                }
            }

            val matching = library.filter { track ->
                track.album.orEmpty().contains(needle, ignoreCase = true) ||
                    track.genre.orEmpty().contains(needle, ignoreCase = true) ||
                    track.title.orEmpty().contains(needle, ignoreCase = true)
            }
            println("DIAG focus '$needle': ${matching.size} треков")
            val byRegion = matching.groupBy { track -> named.indexOfFirst { world -> world.tracks.any { it.id == track.id } } }
            byRegion.entries.sortedByDescending { it.value.size }.forEach { (index, tracks) ->
                val world = named.getOrNull(index)
                println(
                    "DIAG   -> регион[$index] name=${world?.name?.take(34)} size=${world?.tracks?.size} " +
                        "из них совпало=${tracks.size} жанры=${
                            tracks.map { it.genre.orEmpty() }.groupingBy { it }.eachCount().entries
                                .sortedByDescending { it.value }.take(3)
                        }",
                )
            }
            named.forEachIndexed { index, world ->
                val anime = world.tracks.count { track ->
                    val genre = library.firstOrNull { it.id == track.id }?.genre.orEmpty()
                    genre.contains("anime", ignoreCase = true) || genre.contains("soundtrack", ignoreCase = true)
                }
                if (anime > 0) {
                    println(
                        "DIAG   регион[$index] name=${world.name.take(34)} size=${world.tracks.size} " +
                            "аниме/саундтрек-тегов=$anime",
                    )
                }
            }
        }

        val stored = readStoredLayout(File(dir, "map_layout.tsv"))
        if (stored.isNotEmpty()) {
            val space = modes.first().second ?: return
            val worlds = LibraryWorlds.discover(library, vectorsByMode.getValue("fused(0.75 metadata)"), space.dim)
            println("DIAG stored layout from the device: ${stored.size} points, ${worlds.size} worlds")
            reportVisual("stored layout", worlds, stored)
        }
    }

    @Test
    fun `bench fixture`() {
        val dir = System.getenv("MAP_DIAG_BENCH") ?: return
        val idsFile = File(dir, "ids.txt").takeIf { it.isFile } ?: File(dir, "../input/ids.txt")
        val ids = idsFile.readLines().filter { it.isNotBlank() }
        val meta = File(dir, "meta.tsv").readLines().filter { it.isNotBlank() }.map { it.split("\t") }
        val flat = File(dir, "audio.f32").readBytes()
        val dim = 960
        val rows = FloatArray(flat.size / 4)
        ByteBuffer.wrap(flat).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rows)
        val count = minOf(ids.size, meta.size, rows.size / dim)
        val audioMap = HashMap<TrackId, FloatArray>(count)
        val library = ArrayList<TrackDescriptor>(count)
        for (i in 0 until count) {
            val id = TrackId(ids[i])
            audioMap[id] = rows.copyOfRange(i * dim, (i + 1) * dim)
            val cells = meta[i]
            library += TrackDescriptor(
                id = id,
                title = cells.getOrNull(0)?.takeIf { it.isNotBlank() },
                artist = cells.getOrNull(1)?.takeIf { it.isNotBlank() },
                album = cells.getOrNull(2)?.takeIf { it.isNotBlank() },
                genre = cells.getOrNull(3)?.takeIf { it.isNotBlank() },
                year = cells.getOrNull(4)?.trim()?.toIntOrNull(),
            )
        }
        val space = LibraryVectorFusion.build(
            ids = library.map { it.id }, audio = audioMap, metadata = emptyMap(),
            audioDim = dim, metadataDim = 384, minHybridCoverage = 0f,
        ) ?: return
        println("DIAG bench: ${space.size} covered of ${library.size} (audio only, genre labels available)")
        val vectors = vectorsOf(space)
        report("bench audio only", space, vectors, library)
    }

    private fun vectorsOf(space: LibraryVectorSpace): Map<TrackId, FloatArray> =
        space.trackIds.associateWith { requireNotNull(space.vector(it)) }

    private fun report(
        label: String,
        space: LibraryVectorSpace,
        vectors: Map<TrackId, FloatArray>,
        library: List<TrackDescriptor>,
    ) {
        val worlds = LibraryWorlds.discover(library, vectors, space.dim)
        val regionOf = HashMap<TrackId, Int>()
        worlds.forEachIndexed { index, world -> world.tracks.forEach { regionOf[it.id] = index } }
        val sizes = worlds.map { it.tracks.size }.sorted()
        val base = randomPairCosine(vectors)
        val cohesion = worlds.map { world ->
            val unit = world.tracks.map { normalize(vectors.getValue(it.id)) }
            unit.map { dot(it, centroid(unit)) }.average()
        }
        val tightness = (cohesion.average() - base) / (1 - base)
        val knnSpace = neighbourPurity(vectors, space.dim, regionOf, k = 10)
        println(
            "DIAG [$label] worlds=${worlds.size} claimed=${regionOf.size}/${space.size} " +
                "sizes=${sizes.firstOrNull()}..${sizes.getOrNull(sizes.size / 2)}..${sizes.lastOrNull()}",
        )
        println(
            "DIAG [$label] cohesion=${f(cohesion.average())} (base=${f(base)} -> tightness=${f(tightness)}) " +
                "worst=${f(cohesion.min())} kNN10-in-own-world=${f(knnSpace)}",
        )
        val genrePurity = worlds.map { world ->
            val families = world.tracks.map { Genres.families(library.firstOrNull { t -> t.id == it.id }?.genre) }
            val labelled = families.count { it.isNotEmpty() }
            val dominant = families.flatten().groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
            if (labelled == 0) Double.NaN else dominant.toDouble() / labelled
        }.filter { !it.isNaN() }
        if (genrePurity.isNotEmpty()) {
            println(
                "DIAG [$label] genre purity mean=${f(genrePurity.average())} " +
                    "worlds<50%=${genrePurity.count { it < 0.5 }}/${genrePurity.size}",
            )
        }
        val named = LibraryWorlds.namedAfterGroups(
            worlds,
            library.filter { !it.album.isNullOrBlank() }.groupBy { it.album.orEmpty() }
                .filterValues { it.size > 1 }
                .map { (album, tracks) -> album to tracks.mapTo(HashSet()) { it.id } },
        )
        named.forEachIndexed { index, world ->
            val facts = world.tracks.take(1).mapNotNull { t -> library.firstOrNull { it.id == t.id } }
            val cohesionWorld = world.tracks.map { normalize(vectors.getValue(it.id)) }
                .map { dot(it, centroid(world.tracks.map { t -> normalize(vectors.getValue(t.id)) })) }.average()
            println(
                "DIAG [$label] world[$index] size=${world.tracks.size} cohesion=${f(cohesionWorld)} " +
                    "name=${world.name.take(30)} rep=${facts.firstOrNull()?.artist?.take(18)}/" +
                    "${facts.firstOrNull()?.genre?.take(14)}",
            )
        }
        val layout = LibraryLayout.compute(space).associate { it.trackId to floatArrayOf(it.x, it.y) }
        reportVisual("computed layout", worlds, layout)
        if (label.startsWith("fused")) {
            reportCauses(library, vectors, space, regionOf)
            println("DIAG [$label] k-sweep (space kNN10=${f(knnSpace)}):")
            for (candidate in listOf(6, 8, 10, 12, 16, 20, 24, 32)) {
                if (candidate > space.size / TrackClustering.MIN_CLUSTER_SIZE) continue
                val sweep = LibraryWorlds.discover(library, vectors, space.dim, k = candidate)
                val sweepOf = HashMap<TrackId, Int>()
                sweep.forEachIndexed { index, world -> world.tracks.forEach { sweepOf[it.id] = index } }
                val sweepCohesion = sweep.map { world ->
                    val unit = world.tracks.map { normalize(vectors.getValue(it.id)) }
                    unit.map { dot(it, centroid(unit)) }.average()
                }
                println(
                    "DIAG   k=$candidate worlds=${sweep.size} claimed=${sweepOf.size}/${space.size} " +
                        "cohesion=${f(sweepCohesion.average())} " +
                        "tightness=${f((sweepCohesion.average() - base) / (1 - base))} " +
                        "kNN10=${f(neighbourPurity(vectors, space.dim, sweepOf, k = 10))}",
                )
            }
        }
    }

    /**
     * The picture versus the regions, in the square the user actually sees: plain Euclidean
     * distances, neighbour agreement against both a random-label expectation and shuffled
     * realizations, and label placement at the mean and at the median of the members.
     */
    private fun reportVisual(label: String, worlds: List<LibraryWorld>, positions: Map<TrackId, FloatArray>) {
        val dots = worlds.flatMapIndexed { index, world ->
            world.tracks.mapNotNull { track -> positions[track.id]?.let { Triple(index, track.id, it) } }
        }
        val n = dots.size
        if (n < 10) return
        val space = n.toDouble() * (n - 1) / 2
        var sameSum = 0.0
        var differentSum = 0.0
        var pairs = 0
        for (i in 0 until n) for (j in i + 1 until n) {
            val distance = distance(dots[i].third, dots[j].third)
            if (dots[i].first == dots[j].first) {
                sameSum += distance
                pairs++
            } else {
                differentSum += distance
            }
        }
        val meanOwn = HashMap<Int, FloatArray>()
        val medianOwn = HashMap<Int, FloatArray>()
        for (world in worlds.indices) {
            val members = dots.filter { it.first == world }.map { it.third }
            if (members.isEmpty()) continue
            val mean = FloatArray(2)
            members.forEach { mean[0] += it[0]; mean[1] += it[1] }
            meanOwn[world] = floatArrayOf(mean[0] / members.size, mean[1] / members.size)
            medianOwn[world] = floatArrayOf(
                members.map { it[0] }.sorted()[members.size / 2],
                members.map { it[1] }.sorted()[members.size / 2],
            )
        }
        var meanWins = 0
        var medianWins = 0
        for ((region, _, point) in dots) {
            if (meanOwn.keys.minByOrNull { distance(point, meanOwn.getValue(it)) } == region) meanWins++
            if (medianOwn.keys.minByOrNull { distance(point, medianOwn.getValue(it)) } == region) medianWins++
        }
        val neighbours = neighbours(dots.map { it.third }, k = 10)
        val regionLabels = dots.map { it.first }
        val observed = purity(neighbours, regionLabels)
        val expected = regionLabels.groupingBy { it }.eachCount().entries.sumOf { (_, size) ->
            size.toDouble() * (size - 1) / (n - 1)
        } / n
        val shuffled = (1..5).map {
            val shuffledLabels = regionLabels.shuffled(Random(it))
            purity(neighbours, shuffledLabels)
        }.average()
        println(
            "DIAG [$label] dots=$n in-region mean-distance=${f(sameSum / pairs.coerceAtLeast(1))} " +
                "vs cross-region=${f(differentSum / (space - pairs).coerceAtLeast(1.0))}",
        )
        println(
            "DIAG [$label] label placement: nearest-mean-centroid=$meanWins/$n " +
                "nearest-median=$medianWins/$n",
        )
        println(
            "DIAG [$label] kNN10 same-region=${f(observed)} vs random expectation=${f(expected)} " +
                "vs shuffled=${f(shuffled)} (lift=${f(observed / expected)})",
        )
    }

    private fun reportCauses(
        library: List<TrackDescriptor>,
        vectors: Map<TrackId, FloatArray>,
        space: LibraryVectorSpace,
        claimed: Map<TrackId, Int>,
    ) {
        val clusters = TrackClustering.cluster(space.trackIds, vectors, space.dim, k = LibraryWorlds.recommendedK(space.size))
        val inClusters = clusters.flatMapTo(HashSet()) { it.members }
        val survivors = HashSet<TrackId>()
        var belowSimilarity = 0
        var belowMargin = 0
        for (cluster in clusters) {
            val similarities = cluster.memberships.map { it.centroidSimilarity }
            val margins = cluster.memberships.map { it.assignmentMargin }.sorted()
            val similarityFloor = maxOf(
                LibraryWorlds.MIN_CENTROID_SIMILARITY,
                (similarities.maxOrNull() ?: 0f) * LibraryWorlds.MIN_RELATIVE_CENTROID_SIMILARITY,
            )
            val marginFloor = maxOf(
                LibraryWorlds.MIN_ASSIGNMENT_MARGIN,
                (margins.getOrNull(margins.size / 2) ?: 0f) * LibraryWorlds.MIN_RELATIVE_ASSIGNMENT_MARGIN,
            )
            for (membership in cluster.memberships) {
                when {
                    membership.centroidSimilarity < similarityFloor -> belowSimilarity++
                    membership.assignmentMargin < marginFloor -> belowMargin++
                    else -> survivors += membership.trackId
                }
            }
        }
        val droppedByClustering = space.size - inClusters.size
        println(
            "DIAG unclaimed=${space.size - claimed.size} of ${space.size}: " +
                "dropped-by-clustering=$droppedByClustering below-similarity=$belowSimilarity " +
                "below-margin=$belowMargin name-or-thin-route=${survivors.count { it !in claimed }} " +
                "(checksum=${droppedByClustering + belowSimilarity + belowMargin + survivors.count { it !in claimed }})",
        )
    }

    private fun neighbourPurity(
        vectors: Map<TrackId, FloatArray>,
        dim: Int,
        regionOf: Map<TrackId, Int>,
        k: Int,
    ): Double {
        val ids = vectors.keys.toList()
        val rows = ids.map { normalize(vectors.getValue(it)) }
        var hits = 0
        var total = 0
        for (i in ids.indices) {
            val own = regionOf[ids[i]] ?: continue
            val best = IntArray(k) { -1 }
            val bestScore = FloatArray(k) { -2f }
            for (j in ids.indices) {
                if (i == j) continue
                val score = dot(rows[i], rows[j])
                var slot = -1
                for (s in 0 until k) if (score > bestScore[s]) { slot = s; break }
                if (slot >= 0) {
                    for (s in k - 1 downTo slot + 1) {
                        bestScore[s] = bestScore[s - 1]
                        best[s] = best[s - 1]
                    }
                    bestScore[slot] = score
                    best[slot] = j
                }
            }
            for (slot in 0 until k) {
                val neighbour = best[slot]
                if (neighbour >= 0 && regionOf[ids[neighbour]] == own) hits++
                if (neighbour >= 0) total++
            }
        }
        return if (total == 0) Double.NaN else hits.toDouble() / total
    }

    private fun neighbours(points: List<FloatArray>, k: Int): List<IntArray> = points.indices.map { i ->
        points.indices.filter { it != i }
            .sortedBy { distance(points[i], points[it]) }
            .take(k)
            .toIntArray()
    }

    private fun purity(neighbours: List<IntArray>, labels: List<Int>): Double {
        var hits = 0
        var total = 0
        neighbours.forEachIndexed { i, row ->
            for (j in row) {
                if (labels[j] == labels[i]) hits++
                total++
            }
        }
        return if (total == 0) Double.NaN else hits.toDouble() / total
    }

    private fun randomPairCosine(vectors: Map<TrackId, FloatArray>): Double {
        val rows = vectors.values.map(::normalize)
        if (rows.size < 2) return 0.0
        val random = Random(0x1A7E27)
        var sum = 0.0
        repeat(20_000) {
            val a = rows[random.nextInt(rows.size)]
            val b = rows[random.nextInt(rows.size)]
            sum += dot(a, b)
        }
        return sum / 20_000
    }

    private fun readMetadata(file: File): Map<String, List<String>> {
        if (!file.isFile) return emptyMap()
        val pattern = Regex("^Row: \\d+ _id=(\\d+), (.*)$")
        val out = HashMap<String, List<String>>()
        for (line in file.readLines()) {
            val match = pattern.find(line) ?: continue
            val fields = match.groupValues[2].split(", ")
            val values = HashMap<String, String>()
            for (field in fields) {
                val split = field.indexOf('=')
                if (split > 0) values[field.substring(0, split)] = field.substring(split + 1)
            }
            out[match.groupValues[1]] = listOf(
                match.groupValues[1],
                values["title"].orEmpty(),
                values["artist"].orEmpty(),
                values["album"].orEmpty(),
                values["genre"].orEmpty().takeIf { it != "NULL" }.orEmpty(),
                values["year"].orEmpty().takeIf { it != "NULL" }.orEmpty(),
            )
        }
        return out
    }

    /** `name<TAB>id,id,id`, the device's own playlists, so naming can be reproduced as the app does it. */
    private fun readPlaylists(file: File): List<Pair<String, Set<TrackId>>> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val cells = line.split("\t")
            if (cells.size < 2) return@mapNotNull null
            val ids = cells[1].split(",").filter { it.isNotBlank() }.mapTo(HashSet()) { TrackId(it) }
            if (ids.isEmpty()) null else cells[0] to ids
        }
    }

    private fun readStoredLayout(file: File): Map<TrackId, FloatArray> {
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val cells = line.split("\t")
            if (cells.size < 3) return@mapNotNull null
            val x = cells[1].toFloatOrNull() ?: return@mapNotNull null
            val y = cells[2].toFloatOrNull() ?: return@mapNotNull null
            TrackId(cells[0]) to floatArrayOf(x, y)
        }.toMap()
    }

    private class Npy(val dim: Int, val rows: List<FloatArray>)

    private fun readNpy(file: File): Npy {
        val bytes = file.readBytes()
        require(bytes[0] == 0x93.toByte() && bytes[1] == 'N'.code.toByte()) { "not a npy: $file" }
        val major = bytes[6].toInt()
        val headerStart = if (major == 1) 10 else 12
        val headerLength = if (major == 1) {
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        } else {
            ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val header = String(bytes, headerStart, headerLength, Charsets.US_ASCII)
        val shape = Regex("\\(([^)]*)\\)").find(header)!!.groupValues[1]
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        val count = shape.fold(1) { a, b -> a * b }
        val dataStart = headerStart + headerLength
        val flat = FloatArray(count)
        ByteBuffer.wrap(bytes, dataStart, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(flat)
        val dim = shape.last()
        return Npy(dim, List(shape.first()) { row -> flat.copyOfRange(row * dim, (row + 1) * dim) })
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var norm = 0.0
        for (value in vector) norm += value.toDouble() * value
        val scale = if (norm > 0.0) (1.0 / sqrt(norm)).toFloat() else 0f
        return FloatArray(vector.size) { vector[it] * scale }
    }

    private fun centroid(vectors: List<FloatArray>): FloatArray {
        if (vectors.isEmpty()) return FloatArray(0)
        val out = FloatArray(vectors.first().size)
        for (vector in vectors) for (i in out.indices) out[i] += vector[i]
        return normalize(out)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b.getOrElse(i) { 0f }
        return sum
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        val dx = (a[0] - b[0]).toDouble()
        val dy = (a[1] - b[1]).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun f(value: Double): String =
        if (value.isNaN()) "n/a" else (kotlin.math.round(value * 1000) / 1000).toString()
}
