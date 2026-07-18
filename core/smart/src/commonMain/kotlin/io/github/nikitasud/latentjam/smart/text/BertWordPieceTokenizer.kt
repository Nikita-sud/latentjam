/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * `bert-base-uncased` tokenization, as the `all-MiniLM-L6-v2` text encoder expects it.
 *
 * Two stages, mirroring HuggingFace's `BertTokenizer` with `do_lower_case=True`:
 * a basic pass (clean → space out CJK → split on whitespace → lowercase → strip accents → split on
 * punctuation), then WordPiece — greedy longest-match subwords with `##` continuations, falling back
 * to `[UNK]` for the whole word if any piece fails to match. The result is wrapped in
 * `[CLS] … [SEP]`.
 *
 * This has to agree with the reference tokenizer exactly: differing ids give a different sentence
 * vector, which silently changes retrieval rather than failing loudly.
 */
internal class BertWordPieceTokenizer(
    private val vocab: Map<String, Int>,
    unkToken: String = "[UNK]",
    clsToken: String = "[CLS]",
    sepToken: String = "[SEP]",
    private val maxInputCharsPerWord: Int = 100,
    private val maxLen: Int = 512,
) {
    private val unkId = vocab.getValue(unkToken)
    private val clsId = vocab.getValue(clsToken)
    private val sepId = vocab.getValue(sepToken)

    /** Tokenizes [text] into BERT input ids, including the leading `[CLS]` and trailing `[SEP]`. */
    fun encode(text: String): IntArray {
        val pieces = ArrayList<Int>()
        pieces.add(clsId)
        outer@ for (token in basicTokenize(text)) {
            for (piece in wordPiece(token)) {
                pieces.add(piece)
                // Reserve room for the trailing [SEP]; transformers truncates the same way.
                if (pieces.size >= maxLen - 1) break@outer
            }
        }
        pieces.add(sepId)
        return pieces.toIntArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val prepared = spaceOutCjk(cleanText(text))
        val out = ArrayList<String>()
        for (token in prepared.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            out.addAll(splitOnPunctuation(stripAccents(token.lowercase())))
        }
        return out
    }

    /** Drops null, replacement and control characters; collapses every whitespace kind to a space. */
    private fun cleanText(text: String): String {
        val out = StringBuilder(text.length)
        forEachCodePoint(text) { cp ->
            when {
                cp == 0x0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> out.append(' ')
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** Surrounds every CJK codepoint with spaces, so each becomes its own basic token. */
    private fun spaceOutCjk(text: String): String {
        val out = StringBuilder(text.length)
        forEachCodePoint(text) { cp ->
            if (isCjk(cp)) out.append(' ').appendCodePoint(cp).append(' ') else out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /** NFD-normalise, then drop the non-spacing marks it peeled off. */
    private fun stripAccents(token: String): String {
        val out = StringBuilder(token.length)
        forEachCodePoint(nfdNormalize(token)) { cp ->
            if (!isNonSpacingMark(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /** Emits each punctuation character as its own token; runs of non-punctuation stay together. */
    private fun splitOnPunctuation(token: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        forEachCodePoint(token) { cp ->
            if (isPunctuation(cp)) {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
                out.add(codePointToString(cp))
            } else {
                current.appendCodePoint(cp)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun wordPiece(token: String): List<Int> {
        if (token.length > maxInputCharsPerWord) return listOf(unkId)
        val out = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var matched = -1
            while (start < end) {
                val piece = if (start > 0) "##" + token.substring(start, end) else token.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            // One un-matchable piece makes the WHOLE word [UNK], not just that piece.
            if (matched < 0) return listOf(unkId)
            out.add(matched)
            start = end
        }
        return out
    }

    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || isSpaceSeparator(cp)

    private fun isControl(cp: Int): Boolean =
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) false else isControlCategory(cp)

    /** transformers counts every non-alphanumeric ASCII character as punctuation, plus any `P*`. */
    private fun isPunctuation(cp: Int): Boolean =
        (cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126) ||
            isPunctuationCategory(cp)

    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
            cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Parses a BERT `vocab.txt`: one token per line, line index is the token id. */
        fun parseVocab(lines: Sequence<String>): Map<String, Int> {
            val map = HashMap<String, Int>(32_768)
            var id = 0
            for (line in lines) map[line] = id++
            return map
        }
    }
}

// --- surrogate-pair helpers, since Kotlin common has no codePointAt ------------------------------

private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            i += 2
            0x10000 + ((c.code - 0xD800) shl 10) + (text[i - 1].code - 0xDC00)
        } else {
            i++
            c.code
        }
        action(cp)
    }
}

private fun codePointToString(cp: Int): String =
    if (cp < 0x10000) {
        cp.toChar().toString()
    } else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder = append(codePointToString(cp))
