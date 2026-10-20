/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * Python's html.unescape on what music tags carry (metadata audit 07 #4): numeric references (&#38; &#x26;, with or
 * without the semicolon, invalid code points to U+FFFD), and the named entities of HTML 4 / Latin-1 plus a few common
 * HTML5 ones (the legacy names also without the semicolon, as Python accepts them). Rare HTML5 names stay as written.
 */
internal object HtmlEntities {
    private val NAMED: Map<String, String> = mapOf(
        "AElig" to "\u00c6",
        "AMP" to "\u0026",
        "Aacute" to "\u00c1",
        "Acirc" to "\u00c2",
        "Agrave" to "\u00c0",
        "Aring" to "\u00c5",
        "Atilde" to "\u00c3",
        "Auml" to "\u00c4",
        "COPY" to "\u00a9",
        "Ccedil" to "\u00c7",
        "Dagger" to "\u2021",
        "ETH" to "\u00d0",
        "Eacute" to "\u00c9",
        "Ecirc" to "\u00ca",
        "Egrave" to "\u00c8",
        "Euml" to "\u00cb",
        "GT" to "\u003e",
        "Iacute" to "\u00cd",
        "Icirc" to "\u00ce",
        "Igrave" to "\u00cc",
        "Iuml" to "\u00cf",
        "LT" to "\u003c",
        "Ntilde" to "\u00d1",
        "OElig" to "\u0152",
        "Oacute" to "\u00d3",
        "Ocirc" to "\u00d4",
        "Ograve" to "\u00d2",
        "Oslash" to "\u00d8",
        "Otilde" to "\u00d5",
        "Ouml" to "\u00d6",
        "Prime" to "\u2033",
        "QUOT" to "\u0022",
        "REG" to "\u00ae",
        "Scaron" to "\u0160",
        "THORN" to "\u00de",
        "Uacute" to "\u00da",
        "Ucirc" to "\u00db",
        "Ugrave" to "\u00d9",
        "Uuml" to "\u00dc",
        "Yacute" to "\u00dd",
        "Yuml" to "\u0178",
        "Zcaron" to "\u017d",
        "aacute" to "\u00e1",
        "acirc" to "\u00e2",
        "acute" to "\u00b4",
        "aelig" to "\u00e6",
        "agrave" to "\u00e0",
        "amp" to "\u0026",
        "apos" to "\u0027",
        "aring" to "\u00e5",
        "atilde" to "\u00e3",
        "auml" to "\u00e4",
        "bdquo" to "\u201e",
        "brvbar" to "\u00a6",
        "bull" to "\u2022",
        "ccedil" to "\u00e7",
        "cedil" to "\u00b8",
        "cent" to "\u00a2",
        "circ" to "\u02c6",
        "copy" to "\u00a9",
        "curren" to "\u00a4",
        "dagger" to "\u2020",
        "deg" to "\u00b0",
        "divide" to "\u00f7",
        "eacute" to "\u00e9",
        "ecirc" to "\u00ea",
        "egrave" to "\u00e8",
        "emsp" to "\u2003",
        "ensp" to "\u2002",
        "eth" to "\u00f0",
        "euml" to "\u00eb",
        "euro" to "\u20ac",
        "fnof" to "\u0192",
        "frac12" to "\u00bd",
        "frac14" to "\u00bc",
        "frac34" to "\u00be",
        "gt" to "\u003e",
        "hellip" to "\u2026",
        "iacute" to "\u00ed",
        "icirc" to "\u00ee",
        "iexcl" to "\u00a1",
        "igrave" to "\u00ec",
        "iquest" to "\u00bf",
        "iuml" to "\u00ef",
        "laquo" to "\u00ab",
        "larr" to "\u2190",
        "ldquo" to "\u201c",
        "lrm" to "\u200e",
        "lsaquo" to "\u2039",
        "lsquo" to "\u2018",
        "lt" to "\u003c",
        "macr" to "\u00af",
        "mdash" to "\u2014",
        "micro" to "\u00b5",
        "middot" to "\u00b7",
        "nbsp" to "\u00a0",
        "ndash" to "\u2013",
        "not" to "\u00ac",
        "ntilde" to "\u00f1",
        "oacute" to "\u00f3",
        "ocirc" to "\u00f4",
        "oelig" to "\u0153",
        "ograve" to "\u00f2",
        "ordf" to "\u00aa",
        "ordm" to "\u00ba",
        "oslash" to "\u00f8",
        "otilde" to "\u00f5",
        "ouml" to "\u00f6",
        "para" to "\u00b6",
        "permil" to "\u2030",
        "plusmn" to "\u00b1",
        "pound" to "\u00a3",
        "prime" to "\u2032",
        "quot" to "\u0022",
        "raquo" to "\u00bb",
        "rarr" to "\u2192",
        "rdquo" to "\u201d",
        "reg" to "\u00ae",
        "rlm" to "\u200f",
        "rsaquo" to "\u203a",
        "rsquo" to "\u2019",
        "sbquo" to "\u201a",
        "scaron" to "\u0161",
        "sect" to "\u00a7",
        "shy" to "\u00ad",
        "sup1" to "\u00b9",
        "sup2" to "\u00b2",
        "sup3" to "\u00b3",
        "szlig" to "\u00df",
        "thinsp" to "\u2009",
        "thorn" to "\u00fe",
        "tilde" to "\u02dc",
        "times" to "\u00d7",
        "trade" to "\u2122",
        "uacute" to "\u00fa",
        "ucirc" to "\u00fb",
        "ugrave" to "\u00f9",
        "uml" to "\u00a8",
        "uuml" to "\u00fc",
        "yacute" to "\u00fd",
        "yen" to "\u00a5",
        "yuml" to "\u00ff",
        "zcaron" to "\u017e",
        "zwj" to "\u200d",
        "zwnj" to "\u200c",
    )

    /** Names Python also decodes without the trailing semicolon. */
    private val LEGACY: Set<String> = setOf("AElig", "AMP", "Aacute", "Acirc", "Agrave", "Aring", "Atilde", "Auml", "COPY", "Ccedil", "ETH", "Eacute", "Ecirc", "Egrave", "Euml", "GT", "Iacute", "Icirc", "Igrave", "Iuml", "LT", "Ntilde", "Oacute", "Ocirc", "Ograve", "Oslash", "Otilde", "Ouml", "QUOT", "REG", "THORN", "Uacute", "Ucirc", "Ugrave", "Uuml", "Yacute", "aacute", "acirc", "acute", "aelig", "agrave", "amp", "aring", "atilde", "auml", "brvbar", "ccedil", "cedil", "cent", "copy", "curren", "deg", "divide", "eacute", "ecirc", "egrave", "eth", "euml", "frac12", "frac14", "frac34", "gt", "iacute", "icirc", "iexcl", "igrave", "iquest", "iuml", "laquo", "lt", "macr", "micro", "middot", "nbsp", "not", "ntilde", "oacute", "ocirc", "ograve", "ordf", "ordm", "oslash", "otilde", "ouml", "para", "plusmn", "pound", "quot", "raquo", "reg", "sect", "shy", "sup1", "sup2", "sup3", "szlig", "thorn", "times", "uacute", "ucirc", "ugrave", "uml", "uuml", "yacute", "yen", "yuml")

    private val REF = Regex("&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[A-Za-z][A-Za-z0-9]{0,31};?)")

    fun unescape(s: String): String {
        if ('&' !in s) return s
        return REF.replace(s) { m ->
            val body = m.groupValues[1]
            if (body.startsWith("#")) {
                val digits = body.trimEnd(';')
                val code = runCatching {
                    if (digits.length > 1 && (digits[1] == 'x' || digits[1] == 'X')) digits.substring(2).toLong(16) else digits.substring(1).toLong()
                }.getOrNull() ?: return@replace m.value
                val cp = if (code in 0xD800..0xDFFF || code > 0x10FFFF || code == 0L) 0xFFFD else code.toInt()
                return@replace buildString { appendCodePoint(cp) }
            }
            val name = body.trimEnd(';')
            if (body.endsWith(";")) NAMED[name]?.let { return@replace it }
            // Without the semicolon Python decodes the longest legacy name that prefixes the run.
            for (len in minOf(name.length, 32) downTo 2) {
                val pre = name.substring(0, len)
                if (pre in LEGACY) { NAMED[pre]?.let { return@replace it + body.substring(len) } }
            }
            m.value
        }
    }

    private fun StringBuilder.appendCodePoint(cp: Int) {
        if (cp < 0x10000) append(cp.toChar()) else {
            val v = cp - 0x10000
            append((0xD800 + (v shr 10)).toChar()); append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}
