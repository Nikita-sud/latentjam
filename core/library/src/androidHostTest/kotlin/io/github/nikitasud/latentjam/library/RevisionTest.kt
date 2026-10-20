/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals

/** The registry's compact revision prints back as written, and two are equal exactly when their strings are. */
class RevisionTest {
    private val strings = listOf(
        "", "android-mediastore-v1:4000:1700000000:51000", "android-mediastore-v1:0:-:-",
        "android-mediastore-v1:-:-:-", "android-mediastore-v1:-5:3:9", "android-mediastore-v1:+5:3:9",
        "android-mediastore-v1:05:3:9", "android-mediastore-v1:-0:1:2", "android-mediastore-v1:1:2",
        "android-mediastore-v1:1:2:3:4", "android-mediastore-v1:a:b:c", "android-mediastore-v1:",
        "android-mediastore-v1:${Long.MIN_VALUE}:1:2", "android-mediastore-v1:${Long.MAX_VALUE}:1:2",
        "ios-file-v1:123:456", "android-mediastore-v1:4000:1700000000:51001",
    )

    @Test
    fun roundTripsAndComparesAsItsString() {
        for (text in strings) assertEquals(text, Revision.of(text).toString())
        for (a in strings) for (b in strings) {
            assertEquals(a == b, Revision.of(a) == Revision.of(b), "$a vs $b")
            if (a == b) assertEquals(Revision.of(a).hashCode(), Revision.of(b).hashCode())
        }
        assertEquals(true, Revision.of("").isEmpty())
        assertEquals(false, Revision.of("android-mediastore-v1:-:-:-").isEmpty())
    }
}
