/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The lyrics index keeps a document for every song with lyrics for the life of the process. Its word
 * table used to be an object per word (28 bytes each with its reference): eight kilobytes for a
 * 300-word song, more than its text, and well over a hundred megabytes for a library of ten thousand.
 * It is one array of three numbers a word, and nothing else may grow per word.
 */
internal class LyricSearchDocumentLayoutTest {

    @Test
    fun theWordTableIsOneArrayOfThreeNumbersAWord() {
        val document = assertNotNull(LyricSearchDocument.build("Тихий свет,\nза окном — ещё не вечер"))
        val perWord = LyricSearchDocument::class.java.declaredFields
            .onEach { it.isAccessible = true }
            .filter { it.type != String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(listOf(IntArray::class.java), perWord.map { it.type })
        assertEquals(3 * 7, (perWord.single().get(document) as IntArray).size)
    }
}
