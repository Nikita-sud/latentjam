/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_cover
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_keep
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_more
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_new
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_remove
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_removed
import io.github.nikitasud.latentjam.app.generated.resources.info_cover_replace
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A number and its total on one row, as a tag stores them ("3/12"). Input is digits only. */
@Composable
internal fun NumberPairField(
    numberLabel: String,
    totalLabel: String,
    number: String,
    total: String,
    enabled: Boolean,
    onNumber: (String) -> Unit,
    onTotal: (String) -> Unit,
    numberPlaceholder: String? = null,
    totalPlaceholder: String? = null,
) {
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EditorTextField(
            label = numberLabel,
            value = number,
            onValueChange = { onNumber(numberInput(it)) },
            enabled = enabled,
            keyboardOptions = numeric,
            placeholder = numberPlaceholder,
            modifier = Modifier.weight(1f),
        )
        EditorTextField(
            label = totalLabel,
            value = total,
            onValueChange = { onTotal(numberInput(it)) },
            enabled = enabled,
            keyboardOptions = numeric,
            placeholder = totalPlaceholder,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The cover as it will be after saving, with the actions that change it. [otherPictures] are kept
 * by every edit. Saying so means that removing the cover and seeing another picture take its place
 * is not a surprise (spec §3.4).
 */
@Composable
internal fun CoverEditRow(
    choice: CoverChoice,
    currentUri: String?,
    canRemove: Boolean,
    otherPictures: Int,
    enabled: Boolean,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            uri = when (choice) {
                CoverChoice.Keep -> currentUri
                CoverChoice.Remove -> null
                is CoverChoice.Replace -> tagCoverUri(choice.reference)
            },
            size = 72.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when (choice) {
                        CoverChoice.Keep -> Res.string.info_cover
                        CoverChoice.Remove -> Res.string.info_cover_removed
                        is CoverChoice.Replace -> Res.string.info_cover_new
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onReplace, enabled = enabled) { Text(stringResource(Res.string.info_cover_replace)) }
                when {
                    choice != CoverChoice.Keep ->
                        TextButton(onClick = onKeep, enabled = enabled) { Text(stringResource(Res.string.info_cover_keep)) }
                    canRemove ->
                        TextButton(onClick = onRemove, enabled = enabled) { Text(stringResource(Res.string.info_cover_remove)) }
                }
            }
            if (otherPictures > 0) {
                Text(
                    text = pluralStringResource(Res.plurals.info_cover_more, otherPictures, otherPictures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
