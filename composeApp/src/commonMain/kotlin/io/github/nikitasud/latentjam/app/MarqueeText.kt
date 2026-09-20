/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Single-line names on the active player/detail page; short text does no animation work. */
@Composable
internal fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    enabled: Boolean = true,
) {
    val animate = enabled && !rememberReduceMotion()
    // A new name starts at its beginning even when its measured width matches the old one.
    key(text) {
        Text(
            text = text,
            modifier = modifier.then(
                if (animate) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1_800,
                        repeatDelayMillis = 3_000,
                        spacing = MarqueeSpacing(32.dp),
                        velocity = 28.dp,
                    )
                } else Modifier,
            ),
            style = style,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            textDecoration = textDecoration,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
