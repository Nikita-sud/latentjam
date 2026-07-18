/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the tokenizer against HuggingFace's own output on the whole library plus a set of
 * deliberately awkward strings.
 *
 * Tokenization failures are silent: a wrong id yields a plausible-looking vector that quietly
 * degrades retrieval instead of throwing. Regenerate the fixtures with
 * `tools/make_tokenizer_fixtures.py` if the vocabulary ever changes.
 */
class BertWordPieceTokenizerTest {

    private val tokenizer: BertWordPieceTokenizer by lazy {
        val vocab = File("../../androidApp/src/main/assets/ml/text_vocab.txt")
        assertTrue(vocab.isFile, "vocab asset missing at ${vocab.absolutePath}")
        vocab.useLines { BertWordPieceTokenizer(BertWordPieceTokenizer.parseVocab(it)) }
    }

    @Test
    fun `matches the reference tokenizer on every fixture`() {
        val fixtures = checkNotNull(javaClass.getResourceAsStream("/tokenizer_fixtures.tsv")) {
            "tokenizer_fixtures.tsv missing from test resources"
        }.bufferedReader().readLines().filter { it.isNotEmpty() }
        assertTrue(fixtures.size > 100, "expected the full library in the fixture, got ${fixtures.size}")

        val mismatches = mutableListOf<String>()
        for (line in fixtures) {
            val tab = line.lastIndexOf('\t')
            val text = line.substring(0, tab)
            val expected = line.substring(tab + 1).split(',').map(String::toInt)
            val actual = tokenizer.encode(text).toList()
            if (actual != expected) {
                mismatches += "  ${text.take(60)}\n    expected=$expected\n      actual=$actual"
            }
        }
        assertEquals(
            emptyList(), mismatches,
            "${mismatches.size}/${fixtures.size} strings tokenize differently:\n" +
                mismatches.take(5).joinToString("\n"),
        )
        println("tokenizer: ${fixtures.size} strings identical to the reference")
    }

    @Test
    fun `blank input is still wrapped in CLS and SEP`() {
        assertEquals(listOf(101, 102), tokenizer.encode("").toList())
    }

    @Test
    fun `an over-long word collapses to a single UNK`() {
        assertEquals(listOf(101, 100, 102), tokenizer.encode("a".repeat(150)).toList())
    }
}
