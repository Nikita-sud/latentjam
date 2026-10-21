/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

/** Night r4 (reference.py song_key): lower case, bracketed parts and a " - …" suffix dropped, letters / digits / spaces kept. */
internal object SongKey {
    fun of(title: String?, minChars: Int): String? {
        val t = title.orEmpty().lowercase()
        val sb = StringBuilder()
        var depth = 0
        for (c in t) {
            when {
                c == '(' || c == '[' -> depth++
                (c == ')' || c == ']') && depth > 0 -> depth--
                depth == 0 -> sb.append(c)
            }
        }
        var s = sb.toString()
        for (sep in listOf(" - ", " – ", " — ")) {
            val at = s.indexOf(sep)
            if (at > 0) s = s.substring(0, at)
        }
        val out = StringBuilder()
        for (c in s) out.append(if (c.isLetterOrDigit()) c else ' ')
        val key = out.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        return key.takeIf { it.length >= minChars }
    }
}
