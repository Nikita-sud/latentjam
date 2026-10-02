/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.editor_discard
import io.github.nikitasud.latentjam.app.generated.resources.editor_discard_title
import io.github.nikitasud.latentjam.app.generated.resources.editor_keep_editing
import org.jetbrains.compose.resources.stringResource

/** Quiet resting fields with an explicit focus outline in both appearances. */
@Composable
internal fun EditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    /** Shown in an empty field once it is focused (material3 hides it while the label rests). */
    placeholder: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    /**
     * A line under the field, visible focused or not: the N-track editor's field state. A
     * placeholder cannot carry that, as it only shows once the field is focused.
     */
    supportingText: String? = null,
    supportingTextColor: Color = Color.Unspecified,
) {
    // One structure whether or not the line is shown. Two branches made the field a different
    // composable as the line came and went, so emptying a field, or typing its first letter,
    // dropped focus and closed the keyboard mid-word.
    Column(modifier.fillMaxWidth()) {
        EditorTextFieldBox(
            label, value, onValueChange, Modifier, enabled, isError, keyboardOptions, keyboardActions,
            singleLine, minLines, maxLines, placeholder, trailingIcon,
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = supportingTextColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun EditorTextFieldBox(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    isError: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    singleLine: Boolean,
    minLines: Int,
    maxLines: Int,
    placeholder: String?,
    trailingIcon: (@Composable () -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } },
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        isError = isError,
        shape = shape,
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainerHighest,
            unfocusedContainerColor = colors.surfaceContainerHighest,
            disabledContainerColor = colors.surfaceContainerHighest,
            errorContainerColor = colors.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = colors.onSurfaceVariant,
        ),
        modifier = modifier.fillMaxWidth().border(
            width = 1.dp,
            color = when {
                isError -> colors.error
                focused -> colors.onSurfaceVariant
                else -> Color.Transparent
            },
            shape = shape,
        ),
    )
}

/** Equal, wrapping touch targets; the committing action stays at the trailing side. */
@Composable
internal fun EditorActions(
    cancelLabel: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(
            onClick = onCancel, enabled = !busy,
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(cancelLabel, style = MaterialTheme.typography.labelLarge) }
        Button(
            onClick = onConfirm, enabled = confirmEnabled && !busy,
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The state of a sheet that holds an edit. A dismiss the user did not choose in so many words
 * (Back, a scrim tap, a drag, a key that reaches the drag handle) never closes it while [busy], and
 * with [unsaved] changes it calls [onUnsaved], to ask, instead of discarding them.
 *
 * All three are read when the sheet asks, never taken from the last composition. The veto callback
 * is remembered once: the sheet state is keyed on it, and a new one would replace the state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberEditorSheetState(
    busy: () -> Boolean,
    unsaved: () -> Boolean,
    onUnsaved: () -> Unit,
): SheetState {
    val isBusy by rememberUpdatedState(busy)
    val hasUnsaved by rememberUpdatedState(unsaved)
    val ask by rememberUpdatedState(onUnsaved)
    val confirm = remember {
        { value: SheetValue ->
            when {
                value != SheetValue.Hidden -> true
                isBusy() -> false
                hasUnsaved() -> {
                    ask()
                    false
                }
                else -> true
            }
        }
    }
    return rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
}

/** Asked before an editor sheet closes with changes the user has not saved. */
@Composable
internal fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(Res.string.editor_discard_title)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(Res.string.editor_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(stringResource(Res.string.editor_keep_editing)) }
        },
    )
}
