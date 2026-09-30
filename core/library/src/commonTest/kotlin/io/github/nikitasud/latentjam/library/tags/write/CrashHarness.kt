/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Power lost at every operation of a save — with nothing, a byte-count prefix, or an arbitrary
 * subset of the unforced changes surviving — and then lost again at every operation of the
 * recovery that follows. Whatever happens, the track must end as the original or the verified
 * edit, byte for byte, and the store must end empty.
 */
internal object CrashHarness {
    const val TRACK = "track"

    /** Seeds of [FaultFiles.powerLossSubset] tried at every crash point. */
    private const val SUBSET_SEEDS = 16L

    /** One way the unforced changes can survive a power loss. */
    private class Survival(val label: String, val apply: (FaultFiles) -> Unit)

    private fun ids(): () -> String {
        var n = 0
        return { "w${++n}" }
    }

    /** One save with power lost at operation [crashAt]; the files are left as the crash left them. */
    private fun save(case: WriteFixtures.Case, crashAt: Int, atomic: Boolean): Pair<FaultFiles, WriteResult?> {
        val files = FaultFiles()
        files.put(TRACK, case.original)
        val writer = DurableWriter(files.directory, ids(), if (atomic) files.replacer() else null)
        files.crashAt = crashAt
        val result = try {
            writer.write(TRACK, files.track(TRACK), case.edits, targetFreeBytes = null)
        } catch (_: PowerLoss) {
            null
        }
        return files to result
    }

    fun recoverAll(files: FaultFiles, atomic: Boolean): List<TagRecovery.Outcome> {
        val recovery = TagRecovery(files.directory, if (atomic) files.replacer() else null)
        val outcomes = recovery.pending().map { recovery.recover(it, files.track(it.target)) }
        recovery.sweep()
        return outcomes
    }

    private fun samples(pending: Long): Set<Long> =
        if (pending <= 64) (0..pending).toSet() else setOf(0L, 1L, pending / 2, pending - 1, pending)

    /**
     * Every storage state a crash can leave: nothing unforced landed (not `powerLoss(0)`, which
     * lands a leading length change), byte-count prefixes, and seeded arbitrary subsets — later
     * changes without earlier ones, a length change without its data, torn writes.
     */
    private fun survivals(pending: Long): List<Survival> =
        listOf(Survival("nothing landed") { it.powerLoss { _, _ -> false } }) +
            samples(pending).map { keep -> Survival("keep $keep") { it.powerLoss(keep) } } +
            (0L until SUBSET_SEEDS).map { seed -> Survival("subset $seed") { it.powerLossSubset(seed) } }

    /**
     * The second power loss, during recovery: nothing, `powerLoss(0)`, everything, and one seeded
     * subset that differs per scenario ([seed]).
     */
    private fun lossesDuringRecovery(seed: Long): List<Survival> = listOf(
        Survival("nothing landed") { it.powerLoss { _, _ -> false } },
        Survival("keep 0") { it.powerLoss(0) },
        Survival("everything landed") { it.powerLoss(Long.MAX_VALUE) },
        Survival("subset $seed") { it.powerLossSubset(seed) },
    )

    fun everywhere(case: WriteFixtures.Case, atomic: Boolean) {
        val edited = WriteFixtures.expected(case)
        var crashAt = 1
        while (true) {
            val (probe, finished) = save(case, crashAt, atomic)
            if (finished != null) {
                assertTrue(finished is WriteResult.Saved, "${case.name}: $finished")
                assertWhole(probe, case.original, edited, "${case.name}, no crash")
                return
            }
            survivals(probe.pendingBytes()).forEachIndexed { index, survival ->
                var recoveryCrash = 1
                while (true) {
                    val where = "${case.name}: crash $crashAt, ${survival.label}, recovery crash $recoveryCrash"
                    val seed = (crashAt * 1_000L + index) * 1_000L + recoveryCrash
                    var crashed = false
                    for (again in lossesDuringRecovery(seed)) {
                        val (files, _) = save(case, crashAt, atomic)
                        survival.apply(files)
                        files.crashAt = recoveryCrash
                        crashed = try {
                            recoverAll(files, atomic)
                            false
                        } catch (_: PowerLoss) {
                            true
                        }
                        if (!crashed) {
                            assertWhole(files, case.original, edited, where)
                            break
                        }
                        again.apply(files)
                        val outcomes = recoverAll(files, atomic)
                        if (TagRecovery.Outcome.STUCK in outcomes || TagRecovery.Outcome.FOREIGN in outcomes) {
                            fail("$where, ${again.label} → $outcomes")
                        }
                        assertWhole(files, case.original, edited, "$where, ${again.label}")
                    }
                    if (!crashed) break
                    recoveryCrash++
                }
            }
            crashAt++
        }
    }

    fun assertWhole(files: FaultFiles, original: ByteArray, edited: ByteArray, where: String) {
        val now = files.trackBytes(TRACK)
        assertTrue(now.contentEquals(original) || now.contentEquals(edited), "$where: the track is neither original nor edited")
        assertEquals(emptySet(), files.storeNames(), "$where: the store kept files")
    }
}
