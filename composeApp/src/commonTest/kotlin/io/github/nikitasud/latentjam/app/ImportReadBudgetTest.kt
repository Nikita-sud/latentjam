/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ImportReadBudgetTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun playlistsInEveryEncodingLookLikeText() {
        assertTrue(looksLikeText("#EXTM3U\nMusic/song.mp3\n".encodeToByteArray()))
        assertTrue(looksLikeText("Música/canción.mp3".encodeToByteArray()))
        // UTF-16 carries NUL bytes; its byte order mark says it is text.
        assertTrue(looksLikeText(bytes(0xff, 0xfe, '#'.code, 0, 'E'.code, 0)))
        assertTrue(looksLikeText(bytes(0xfe, 0xff, 0, '#'.code, 0, 'E'.code)))
        // Windows-1252 é.
        assertTrue(looksLikeText(bytes('C'.code, 0xe9, '.'.code, 'm'.code, 'p'.code, '3'.code)))
        assertTrue(looksLikeText(ByteArray(0)))
    }

    @Test
    fun audioAndOtherBinaryFilesDoNot() {
        assertFalse(looksLikeText("ID3".encodeToByteArray() + bytes(4, 0, 0)))
        assertFalse(looksLikeText("fLaC".encodeToByteArray() + bytes(0, 0, 0, 34)))
        assertFalse(looksLikeText("OggS".encodeToByteArray() + bytes(0, 2)))
        assertFalse(looksLikeText("RIFF".encodeToByteArray() + bytes(36, 0, 0, 0) + "WAVE".encodeToByteArray()))
        assertFalse(looksLikeText(bytes(0, 0, 0, 32) + "ftypM4A ".encodeToByteArray()))
        // An MPEG frame without a tag, a JPEG cover.
        assertFalse(looksLikeText(bytes(0xff, 0xfb, 0x90, 0x64)))
        assertFalse(looksLikeText(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertFalse(looksLikeText("plain text then".encodeToByteArray() + bytes(0) + "binary".encodeToByteArray()))
    }

    @Test
    fun aSelectionSharesOneBound() {
        val budget = ImportReadBudget(textOnly = true, total = 10)
        budget.accept("#EXTM3U".encodeToByteArray())
        assertEquals(3, budget.remaining)
        assertEquals(3, budget.nextLimit)
        assertFailsWith<IllegalStateException> { budget.accept("#EXTM3U".encodeToByteArray()) }
        // A refused document costs nothing; a smaller one still fits.
        assertEquals(3, budget.remaining)
        budget.accept("a\n".encodeToByteArray())
        assertEquals(1, budget.remaining)
    }

    @Test
    fun onlyATextOnlyBudgetRefusesBinaryStarts() {
        val audio = "ID3".encodeToByteArray() + bytes(4, 0, 0)
        assertFailsWith<IllegalStateException> { ImportReadBudget(textOnly = true).checkStart(audio) }
        ImportReadBudget(textOnly = false).accept(audio)
    }

    @Test
    fun oneDocumentStaysWithinTheSingleDocumentLimit() {
        assertEquals(MAX_LOCAL_BACKUP_DOCUMENT_CHARS, ImportReadBudget(textOnly = false, total = Int.MAX_VALUE).nextLimit)
    }
}
