/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun trackDeleteStrategy(sdkInt: Int): TrackDeleteStrategy = when {
    sdkInt >= Build.VERSION_CODES.R -> TrackDeleteStrategy.SYSTEM_DELETE_REQUEST
    sdkInt >= Build.VERSION_CODES.Q -> TrackDeleteStrategy.RECOVERABLE_CONSENT
    else -> TrackDeleteStrategy.WRITE_PERMISSION
}

/** Holds only application context; an Activity supplies launchers while its UI is resumed. */
internal class TrackDeleteViewModel(context: Context, handle: SavedStateHandle) : ViewModel() {
    val coordinator = TrackDeleteCoordinator(
        backend = AndroidTrackDeleteBackend(context.applicationContext),
        scope = viewModelScope,
        restored = handle.get<ArrayList<String>>("requests"),
        save = { handle["requests"] = ArrayList(it) },
    )
}

@Composable
actual fun rememberTrackDeleter(onResult: (TrackDeleteReport) -> Unit): (List<TrackDescriptor>) -> Unit {
    val activity = LocalActivity.current as? ComponentActivity
        ?: return { tracks -> onResult(TrackDeleteReport(failed = tracks.size)) }
    val model = remember(activity) {
        val factory = viewModelFactory {
            initializer { TrackDeleteViewModel(activity.applicationContext, createSavedStateHandle()) }
        }
        ViewModelProvider(activity, factory)[TrackDeleteViewModel::class.java]
    }
    val coordinator = model.coordinator
    val currentOnResult by rememberUpdatedState(onResult)
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        coordinator.answer(when {
            result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true ->
                DeleteAnswer.FAILED
            result.resultCode == Activity.RESULT_OK -> DeleteAnswer.APPROVED
            else -> DeleteAnswer.CANCELLED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        coordinator.answer(if (granted) DeleteAnswer.APPROVED else DeleteAnswer.CANCELLED)
    }

    var resumed by remember(activity) { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(coordinator, resumed) {
        if (resumed) coordinator.onHostResumed()
    }
    val prompt by coordinator.prompt.collectAsState()
    LaunchedEffect(prompt, resumed) {
        val pending = prompt
        if (resumed && pending != null && coordinator.promptLaunched(pending.requestId)) {
            try {
                val sender = pending.consent
                if (sender == null) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coordinator.answer(DeleteAnswer.FAILED)
            }
        }
    }
    val completed by coordinator.completed.collectAsState()
    LaunchedEffect(completed, resumed) {
        val request = completed
        if (resumed && request != null) {
            // Consume synchronously with delivery so recomposition cannot emit the same result twice.
            coordinator.acknowledge(request.id)
            currentOnResult(request.report)
        }
    }
    return { tracks -> coordinator.enqueue(tracks.mapNotNull { it.audioUri }) }
}

private class AndroidTrackDeleteBackend(private val context: Context) : TrackDeleteBackend<IntentSender> {
    override val strategy = trackDeleteStrategy(Build.VERSION.SDK_INT)
    override fun hasWritePermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    override suspend fun presence(uri: String): DeletePresence = withContext(Dispatchers.IO) {
        try {
            val cursor = context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID),
                null, null, null)
            cursor?.use { if (it.moveToFirst()) DeletePresence.PRESENT else DeletePresence.MISSING }
                ?: DeletePresence.FAILED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            DeletePresence.DENIED
        } catch (_: Exception) {
            DeletePresence.FAILED
        }
    }

    override suspend fun delete(uri: String): DeleteAttempt<IntentSender> = withContext(Dispatchers.IO) {
        try {
            if (context.contentResolver.delete(Uri.parse(uri), null, null) > 0) DeleteAttempt.Deleted
            else DeleteAttempt.Missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecurityException) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && failure is RecoverableSecurityException) {
                DeleteAttempt.NeedsConsent(failure.userAction.actionIntent.intentSender)
            } else DeleteAttempt.Denied
        } catch (_: Exception) {
            DeleteAttempt.Failed
        }
    }

    override suspend fun batchConsent(uris: List<String>): IntentSender = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        MediaStore.createDeleteRequest(context.contentResolver, uris.map(Uri::parse)).intentSender
    }
}
