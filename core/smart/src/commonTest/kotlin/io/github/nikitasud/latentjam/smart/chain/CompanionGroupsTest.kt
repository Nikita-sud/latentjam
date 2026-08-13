/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The companion bonus is the listener's explicit "keep these together" — playlists they marked —
 * expressed as a fixed additive nudge, NOT a fence. It must decide ties toward a marked
 * companion, and it must lose to genuine acoustic distance: a playlist can say "prefer", never
 * "drag anything from anywhere".
 */
internal class CompanionGroupsTest {

    /**
     * Row 0 is the seed. Rows 1 and 2 sit at the SAME cosine to it (identical seed component,
     * orthogonal noise in different dimensions); rows 3..9 are far. Row 3 is far AND grouped.
     */
    private fun library(): List<SmartTrack> {
        fun track(row: Int, seedComponent: Float, noiseDim: Int): SmartTrack {
            val audio = FloatArray(PredictorRuntime.EMBEDDING_DIM)
            audio[0] = seedComponent
            audio[noiseDim] = sqrt(1f - seedComponent * seedComponent)
            return SmartTrack(
                id = TrackId(row.toString()),
                audio = audio,
                meta = TrackMeta("title$row", "artist$row", null, null, null),
            )
        }
        return buildList {
            add(track(0, 1f, 1))
            add(track(1, 0.9f, 100))
            add(track(2, 0.9f, 200))
            add(track(3, 0.2f, 300))
            for (row in 4 until 10) add(track(row, 0.1f, 300 + row))
        }
    }

    private fun firstPick(groups: List<Set<TrackId>>): TrackId {
        val snapshot = requireNotNull(SmartSnapshot.build(library()))
        val chain = SmartChain(snapshot, runtime = null, companionGroups = groups)
        val result = chain.build(seedId = TrackId("0"), length = 1, timeFeatures = FloatArray(5))
        return snapshot.tracks[result.rows.first()].id
    }

    @Test
    fun `no groups keeps the shipped order`() {
        // Rows 1 and 2 tie acoustically; the chain's deterministic scan keeps the earlier row.
        assertEquals(TrackId("1"), firstPick(emptyList()))
    }

    @Test
    fun `a marked companion wins an acoustic tie`() {
        assertEquals(
            TrackId("2"),
            firstPick(listOf(setOf(TrackId("0"), TrackId("2")))),
        )
    }

    @Test
    fun `the bonus never outweighs genuine acoustic distance`() {
        // Row 3 is grouped with the seed but acoustically remote; the nudge must not drag it in.
        assertEquals(
            TrackId("1"),
            firstPick(listOf(setOf(TrackId("0"), TrackId("3")))),
        )
    }

    @Test
    fun `the smallest shared group outweighs a marked super-playlist`() {
        // Rows 1 and 2 tie acoustically. Row 1 shares only a huge group with the seed (8 of 10
        // rows — an "Anime" that holds most of the library); row 2 also shares a tight one.
        // Specificity must let the tight group win, or marking a super-playlist would hand the
        // same bonus to half the library and drown every sub-playlist inside it.
        val everything = setOf("0", "1", "3", "4", "5", "6", "7", "8").mapTo(HashSet(), ::TrackId)
        val tight = setOf(TrackId("0"), TrackId("2"))
        assertEquals(TrackId("2"), firstPick(listOf(everything, tight)))
    }

    @Test
    fun `a group after the sixty fourth is still honored`() {
        // All first 64 groups are distinct, live memberships containing row 1. Their fixed row 3
        // plus a six-bit subset of rows 4..9 makes 64 unique groups, each broader than the tight
        // 65th group. A machine-word cap would hide the more-specific row-2 preference.
        val groupsBeforeIt = List(64) { mask ->
            buildSet {
                add(TrackId("0"))
                add(TrackId("1"))
                add(TrackId("3"))
                for (bit in 0 until 6) {
                    if (mask and (1 shl bit) != 0) add(TrackId((4 + bit).toString()))
                }
            }
        }
        val sixtyFifth = setOf(TrackId("0"), TrackId("2"))

        assertEquals(TrackId("2"), firstPick(groupsBeforeIt + listOf(sixtyFifth)))
    }

    @Test
    fun `a remote companion still enters the candidate pool`() {
        // 120 tracks around the seed overflow the 100-slot pool; the one marked companion is the
        // single farthest track, which retrieval alone would never surface. With its group
        // marked it must be IN the pool — visible to the bonus — even if it never wins a hop.
        fun track(row: Int, seedComponent: Float, noiseDim: Int): SmartTrack {
            val audio = FloatArray(PredictorRuntime.EMBEDDING_DIM)
            audio[0] = seedComponent
            audio[noiseDim] = sqrt(1f - seedComponent * seedComponent)
            return SmartTrack(
                id = TrackId(row.toString()),
                audio = audio,
                meta = TrackMeta("title$row", "artist$row", null, null, null),
            )
        }
        val library = buildList {
            add(track(0, 1f, 1))
            for (row in 1 until 120) add(track(row, 0.9f - row * 0.001f, 100 + row))
            add(track(120, 0.05f, 400))
        }
        val snapshot = requireNotNull(SmartSnapshot.build(library))
        val companion = setOf(TrackId("0"), TrackId("120"))

        val without = SmartChain(snapshot, runtime = null)
            .build(seedId = TrackId("0"), length = 1, timeFeatures = FloatArray(5))
        val with = SmartChain(snapshot, runtime = null, companionGroups = listOf(companion))
            .build(seedId = TrackId("0"), length = 1, timeFeatures = FloatArray(5))

        val remoteRow = library.indexOfFirst { it.id == TrackId("120") }
        assertEquals(false, remoteRow in without.pool)
        assertEquals(true, remoteRow in with.pool)

        // And the quota guarantees the marked group actually SOUNDS in the queue: every third
        // hop goes to its best available member, so even this remote-only companion appears in
        // a six-track walk — the bonus orders, the quota represents.
        val walk = SmartChain(snapshot, runtime = null, companionGroups = listOf(companion))
            .build(seedId = TrackId("0"), length = 6, timeFeatures = FloatArray(5))
        assertEquals(true, walk.rows.any { library[it].id == TrackId("120") })
        val plainWalk = SmartChain(snapshot, runtime = null)
            .build(seedId = TrackId("0"), length = 6, timeFeatures = FloatArray(5))
        assertEquals(false, plainWalk.rows.any { library[it].id == TrackId("120") })
    }

    @Test
    fun `quota rotates between an overlapping super group and tight group`() {
        fun track(row: Int, seedComponent: Float, noiseDim: Int): SmartTrack {
            val audio = FloatArray(PredictorRuntime.EMBEDDING_DIM)
            audio[0] = seedComponent
            audio[noiseDim] = sqrt(1f - seedComponent * seedComponent)
            return SmartTrack(
                id = TrackId(row.toString()),
                audio = audio,
                meta = TrackMeta("title$row", "artist$row", null, null, null),
            )
        }
        val library = buildList {
            add(track(0, 1f, 1))
            for (row in 1 until 120) add(track(row, 0.95f - row * 0.001f, 100 + row))
            add(track(120, 0.05f, 400))
        }
        val snapshot = requireNotNull(SmartSnapshot.build(library))
        val superGroup = (listOf(TrackId("0")) + (100 until 120).map { TrackId(it.toString()) }).toSet()
        val tightGroup = setOf(TrackId("0"), TrackId("120"))
        val result = SmartChain(
            snapshot,
            runtime = null,
            // Duplicate memberships must collapse; otherwise the super group would own both of
            // the first two quota turns and starve the tight playlist until hop nine.
            companionGroups = listOf(superGroup, superGroup.toSet(), tightGroup),
        ).build(seedId = TrackId("0"), length = 6, timeFeatures = FloatArray(5))
        val ids = result.rows.map { snapshot.tracks[it].id }
        val reorderedIds = SmartChain(
            snapshot,
            runtime = null,
            companionGroups = listOf(tightGroup, superGroup),
        ).build(seedId = TrackId("0"), length = 6, timeFeatures = FloatArray(5))
            .rows.map { snapshot.tracks[it].id }

        assertEquals(true, ids[2] in superGroup)
        assertEquals(TrackId("120"), ids[5])
        assertEquals(ids, reorderedIds, "playlist drag order is not SMART policy")
    }
}
