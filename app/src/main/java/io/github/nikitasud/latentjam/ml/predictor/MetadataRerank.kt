/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * MetadataRerank.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.predictor

import io.github.nikitasud.latentjam.ml.data.TrackMetadataResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import org.oxycblt.musikr.Song

/**
 * Multiplicative metadata-aware rerank applied on top of cosine retrieval scores.
 *
 * Reads metadata via [TrackMetadataResolver] so user-supplied overrides (from the in-app edit
 * dialog) take precedence over file-embedded tags. Validated on the laptop archive — the
 * cross-language penalty alone catches the worst encoder failures (Beyoncé "Crazy in Love" →
 * Russian wartime march; Ария power metal → Chicago/Aerosmith).
 *
 * Per candidate the adjusted score is:
 *
 * adjusted = cosine × (1 ± same_genre_bonus) if both have genre tags × (1 - cross_language_penalty)
 * if seed and candidate detected languages differ × (1 - era_decade_penalty × Δdec) when both have
 * year metadata
 * - same_album_penalty if same album as seed
 * - same_artist_dup_penalty × count if the artist already appears earlier in the beam
 */
@Singleton
class MetadataRerank @Inject constructor(private val metadata: TrackMetadataResolver) {

    data class Config(
        val sameGenreBonus: Float = 0.20f,
        val crossLanguagePenalty: Float = 0.25f,
        val sameAlbumPenalty: Float = 1.0f,
        val sameArtistDupPenalty: Float = 0.30f,
        val eraDecadePenalty: Float = 0.04f,
    )

    /** Coarse language hint: ru / ja / en (default). Detects on artist+title text. */
    fun detectLanguage(song: Song): String {
        val text = buildString {
            append(song.name.raw)
            metadata.effectiveArtist(song)?.let {
                append(' ')
                append(it)
            }
        }
        for (c in text) {
            if (c in 'Ѐ'..'ӿ') return "ru"
            if (c in '぀'..'ヿ' || c in '一'..'鿿') return "ja"
        }
        return "en"
    }

    /**
     * Coarse genre coalescing. Returns null for missing/unknown so we can avoid penalizing untagged
     * tracks. The buckets match the laptop eval — rap, rock, pop, dance, classical, soundtrack —
     * plus the raw tag for everything else.
     */
    fun normalizeGenre(song: Song): String? {
        val raw = metadata.effectiveGenre(song)?.lowercase()?.trim() ?: return null
        if (raw.isEmpty() || raw == "<unknown>" || raw == "unknown" || raw == "other") return null
        return when {
            "hip" in raw || "rap" in raw || "trap" in raw || "phonk" in raw -> "rap"
            "rock" in raw || "metal" in raw || "punk" in raw || "grunge" in raw -> "rock"
            "pop" in raw -> "pop"
            "dance" in raw ||
                "electronic" in raw ||
                "edm" in raw ||
                "house" in raw ||
                "techno" in raw -> "dance"
            "classical" in raw || "orchestral" in raw || "baroque" in raw -> "classical"
            "soundtrack" in raw || "score" in raw -> "soundtrack"
            else -> raw
        }
    }

    fun albumKey(song: Song): String? {
        val raw = (song.album.name as? org.oxycblt.musikr.tag.Name.Known)?.raw
        return raw?.takeIf { it.isNotBlank() }
    }

    /**
     * Adjust [baseCosine] by metadata bonuses/penalties relative to the seed. Does NOT apply
     * artist-diversity penalty — that's positional and lives in the greedy pick loop in
     * [rerankBeam].
     */
    fun pairwiseAdjust(
        baseCosine: Float,
        seed: Song,
        seedLang: String,
        seedGenre: String?,
        seedYear: Int?,
        seedAlbum: String?,
        candidate: Song,
        cfg: Config = Config(),
    ): Float {
        var s = baseCosine

        val candAlbum = albumKey(candidate)
        if (seedAlbum != null && candAlbum != null && candAlbum == seedAlbum) {
            s -= cfg.sameAlbumPenalty
        }

        val candGenre = normalizeGenre(candidate)
        if (seedGenre != null && candGenre != null) {
            s *=
                if (seedGenre == candGenre) (1f + cfg.sameGenreBonus)
                else (1f - cfg.sameGenreBonus * 0.5f)
        }

        val candLang = detectLanguage(candidate)
        if (candLang != seedLang) {
            s *= (1f - cfg.crossLanguagePenalty)
        }

        val candYear = metadata.effectiveYear(candidate)
        if (seedYear != null && candYear != null) {
            val decades = abs(seedYear - candYear) / 10f
            s *= (1f - cfg.eraDecadePenalty * decades)
        }

        return s
    }

    /**
     * Rerank the cosine-sorted [candidates] using metadata signals and an artist-diversity penalty,
     * returning at most [k] picks ordered by adjusted score. Each item is `(uidIndex,
     * baseCosineScore, song)`.
     */
    fun rerankBeam(
        seed: Song,
        candidates: List<Triple<Int, Float, Song>>,
        k: Int,
        cfg: Config = Config(),
    ): List<Triple<Int, Float, Song>> {
        val seedLang = detectLanguage(seed)
        val seedGenre = normalizeGenre(seed)
        val seedYear = metadata.effectiveYear(seed)
        val seedAlbum = albumKey(seed)

        val adjusted = ArrayList<Triple<Int, Float, Song>>(candidates.size)
        for ((idx, base, song) in candidates) {
            val s = pairwiseAdjust(base, seed, seedLang, seedGenre, seedYear, seedAlbum, song, cfg)
            adjusted.add(Triple(idx, s, song))
        }
        adjusted.sortByDescending { it.second }

        val chosen = ArrayList<Triple<Int, Float, Song>>(k)
        val artistCounts = HashMap<String, Int>()
        for ((idx, base, song) in adjusted) {
            if (chosen.size == k) break
            val artist = metadata.effectiveArtist(song) ?: ""
            val dup = artistCounts[artist] ?: 0
            val finalScore = base - cfg.sameArtistDupPenalty * dup
            chosen.add(Triple(idx, finalScore, song))
            artistCounts[artist] = dup + 1
        }
        chosen.sortByDescending { it.second }
        return chosen
    }
}
