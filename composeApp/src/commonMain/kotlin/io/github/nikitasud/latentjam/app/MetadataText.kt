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
    for (char in text) {
        when {
            char == '\u200F' -> return LayoutDirection.Rtl // RIGHT-TO-LEFT MARK
            char == '\u200E' -> return LayoutDirection.Ltr // LEFT-TO-RIGHT MARK
            !char.isLetter() -> continue
            char.isInRightToLeftScript() -> return LayoutDirection.Rtl
            else -> return LayoutDirection.Ltr
        }
    }
    return null
}

private fun Char.isInRightToLeftScript(): Boolean {
    val code = code
    return code in 0x0590..0x08FF || // Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic
        code in 0xFB1D..0xFDFF || // Hebrew and Arabic presentation forms A
        code in 0xFE70..0xFEFF // Arabic presentation forms B
}

/**
 * [text] between FIRST STRONG ISOLATE and POP DIRECTIONAL ISOLATE, for metadata placed inside a
 * longer string: a translated sentence ("Created “%1$s”") or a " · " line of several facts.
 * Inside the isolate the text takes its own direction, and nothing in it can pull the
 * surrounding punctuation or numbers across: in Hebrew, “100 Rock Hits” stays one piece and
 * the quotes stay on its two sides. Both marks are invisible.
 */
internal fun isolate(text: String): String = if (text.isEmpty()) text else "⁨$text⁩"

/** [parts] joined by [separator], each part isolated, for a line made of several facts. */
internal fun joinIsolated(parts: Iterable<String>, separator: String = " · "): String =
    parts.joinToString(separator) { isolate(it) }
