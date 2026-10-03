/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/*
 * Text the user's files supply — titles, artists, albums, genres, file names, playlist names,
 * lyrics — is not written in the app's language. By default Compose gives every paragraph the
 * layout's direction, so in Hebrew or Arabic "100 Rock Hits" becomes an RTL paragraph and reads
 * "Rock Hits 100", and "Boney M." grows its full stop on the wrong side. Metadata takes its
 * paragraph direction from its own first strong character instead (TextDirection.Content, what
 * Android's TextView calls firstStrong), while staying aligned to the layout's start edge, so a
 * Latin title in a Hebrew list still lines up with its Hebrew neighbours.
 *
 * Translated UI strings keep the default: they are in the layout's language by construction.
 */

/** This style for metadata: direction from the text itself, alignment kept on the layout's side. */
internal fun TextStyle.forMetadata(layoutDirection: LayoutDirection): TextStyle {
    val rtl = layoutDirection == LayoutDirection.Rtl
    // TextAlign.Start follows the paragraph's direction, so with Content direction it would flip
    // a Latin title to the left edge of a right-to-left screen. Pin Start and End to the
    // layout's edges instead; Center, Justify, Left and Right already mean the same either way.
    val anchored = when (textAlign) {
        TextAlign.Start, TextAlign.Unspecified -> if (rtl) TextAlign.Right else TextAlign.Left
        TextAlign.End -> if (rtl) TextAlign.Left else TextAlign.Right
        else -> textAlign
    }
    return copy(textDirection = TextDirection.Content, textAlign = anchored)
}

/** [forMetadata] for the current layout direction. */
@Composable
@ReadOnlyComposable
internal fun TextStyle.metadata(): TextStyle = forMetadata(LocalLayoutDirection.current)

/**
 * The direction of [text]'s first strong character (the Unicode bidi "first strong" rule,
 * reduced to the scripts a music library realistically holds): Hebrew, Arabic and their
 * relatives are right-to-left, any other letter is left-to-right. Digits, punctuation and
 * symbols carry no direction, so text made only of them yields null.
 */
internal fun firstStrongDirection(text: CharSequence): LayoutDirection? {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        strongDirection(codePoint)?.let { return it }
        index += if (codePoint > 0xFFFF) 2 else 1
    }
    return null
}

/** The direction of [text]'s last strong character; null when it has none. */
internal fun lastStrongDirection(text: CharSequence): LayoutDirection? {
    var index = text.length - 1
    while (index >= 0) {
        val low = text[index]
        val codePoint = if (low.isLowSurrogate() && index > 0 && text[index - 1].isHighSurrogate()) {
            index--
            supplementary(text[index], low)
        } else {
            low.code
        }
        strongDirection(codePoint)?.let { return it }
        index--
    }
    return null
}

private fun CharSequence.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return supplementary(high, this[index + 1])
    }
    return high.code
}

private fun supplementary(high: Char, low: Char): Int =
    ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

/** Strong bidi direction of one code point, or null for digits, punctuation and symbols. */
private fun strongDirection(codePoint: Int): LayoutDirection? = when {
    codePoint == 0x200F -> LayoutDirection.Rtl // RIGHT-TO-LEFT MARK
    codePoint == 0x200E -> LayoutDirection.Ltr // LEFT-TO-RIGHT MARK
    codePoint > 0xFFFF -> when (codePoint) {
        // Old Hebrew-family and African right-to-left scripts, Adlam, Arabic mathematical letters.
        in 0x10800..0x10FFF, in 0x1E800..0x1EFFF -> LayoutDirection.Rtl
        // Emoji, pictographs and other symbols carry no direction.
        in 0x1F000..0x1FFFF -> null
        // Everything else up there is a left-to-right script (CJK extensions, Gothic, …).
        else -> LayoutDirection.Ltr
    }
    !codePoint.toChar().isLetter() -> null
    codePoint in 0x0590..0x08FF || // Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic
        codePoint in 0xFB1D..0xFDFF || // Hebrew and Arabic presentation forms A
        codePoint in 0xFE70..0xFEFF -> LayoutDirection.Rtl // Arabic presentation forms B
    else -> LayoutDirection.Ltr
}

private const val LRM = '\u200E'
private const val RLM = '\u200F'
private const val LRE = '\u202A'
private const val RLE = '\u202B'
private const val PDF = '\u202C'

/**
 * [text] made safe to place inside a longer string laid out in [context] direction: a
 * translated sentence ("Created “%1$s”") or a " · " line of several facts. Nothing in the
 * piece can pull the surrounding punctuation or numbers across, and the piece keeps its own
 * order: in an English line, the album “שירים 2020” still reads “2020 שירים”.
 *
 * This is android.text.BidiFormatter.unicodeWrap, written out for common code. A piece whose
 * direction differs from the context goes inside an embedding (LRE/RLE … PDF), and a mark of the
 * context's direction is set before and after it when its first or last strong character would
 * otherwise reach across. Embeddings and marks rather than the newer isolates (FSI … PDI): the
 * isolates reorder digits wrongly on Android 7 and 11, while these controls have rendered
 * correctly everywhere Android and Skia ever drew text. All of them are invisible.
 *
 * Text without a strong character counts as left-to-right, as in BidiFormatter.
 */
internal fun bidiWrap(text: String, context: LayoutDirection): String {
    if (text.isEmpty()) return text
    val first = firstStrongDirection(text)
    val rtl = first == LayoutDirection.Rtl
    val rtlContext = context == LayoutDirection.Rtl
    val last = lastStrongDirection(text)
    return buildString(text.length + 4) {
        when {
            !rtlContext && rtl -> append(LRM)
            rtlContext && !rtl -> append(RLM)
        }
        if (rtl != rtlContext) {
            append(if (rtl) RLE else LRE)
            append(text)
            append(PDF)
        } else {
            append(text)
        }
        when {
            !rtlContext && (rtl || last == LayoutDirection.Rtl) -> append(LRM)
            rtlContext && (!rtl || last == LayoutDirection.Ltr) -> append(RLM)
        }
    }
}

/** [parts] joined by [separator], each part wrapped by [bidiWrap] for [context]. */
internal fun joinBidiWrapped(
    parts: Iterable<String>,
    context: LayoutDirection,
    separator: String = " · ",
): String = parts.joinToString(separator) { bidiWrap(it, context) }

/** [bidiWrap] for the current layout direction: call it where the string is displayed. */
@Composable
@ReadOnlyComposable
internal fun bidiWrap(text: String): String = bidiWrap(text, LocalLayoutDirection.current)

/** [joinBidiWrapped] for the current layout direction. */
@Composable
@ReadOnlyComposable
internal fun joinBidiWrapped(parts: Iterable<String>, separator: String = " · "): String =
    joinBidiWrapped(parts, LocalLayoutDirection.current, separator)
