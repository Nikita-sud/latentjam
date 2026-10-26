/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Listens for the screen turning off while the "Player on the lock screen" setting is on, and
 * opens [LockScreenPlayerActivity] then. The receiver exists only while the setting is on.
 *
 * Android lets an app start an activity from the background only with an exemption. A media
 * foreground service is none (measured on Android 16: BAL_BLOCK), so this works when LatentJam
 * itself is on screen at the moment the screen goes off, which counts as foreground. From any
 * other app the start is silently refused and the system media card stays the lock-screen player.
 */
internal fun installLockScreenPlayer(context: Context) {
    val app = context.applicationContext
    val trigger = LockPlayerTrigger()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> trigger.onScreenOn()
                Intent.ACTION_SCREEN_OFF -> {
                    val open = trigger.onScreenOff(
                        enabled = AppGraph.settings.lockScreenPlayer.value,
                        playing = AppGraph.playback.state.value.isPlaying,
                        playerShowing = LockScreenPlayerActivity.showing,
                    )
                    if (open) {
                        runCatching {
                            app.startActivity(
                                Intent(app, LockScreenPlayerActivity::class.java).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                                ),
                            )
                        }.onFailure { println("Lock player: could not open: $it") }
                    }
                }
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
    }
    // Main: the receiver runs there too, so the trigger is only ever touched from one thread.
    AppGraph.appScope.launch(Dispatchers.Main) {
        var registered = false
        AppGraph.settings.lockScreenPlayer.collect { enabled ->
            if (enabled && !registered) {
                ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            } else if (!enabled && registered) {
                app.unregisterReceiver(receiver)
                registered = false
                trigger.onScreenOn()
            }
        }
    }
}

/**
 * The full player above the keyguard, edge to edge. It drives the same playback controller as the
 * app, so it always agrees with the media notification. It leaves when the device is unlocked or
 * playback ends; its close action asks the system to unlock rather than dropping back to the lock
 * screen.
 */
class LockScreenPlayerActivity : ComponentActivity() {

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) leave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        showOverKeyguard(true)
        applyStoredWindowTheme()
        // Exported on purpose: System UI, an app uid, sends USER_PRESENT, so a not-exported
        // receiver never hears the unlock. Only the system may send this protected broadcast.
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                AppGraph.playback.state
                    .map(::lockPlayerShouldClose)
                    .distinctUntilChanged()
                    .collect { close -> if (close) leave() }
            }
        }
        setContent {
            LockScreenPlayer(playback = AppGraph.playback, onClose = ::unlock)
        }
    }

    /**
     * The screen going off stops the player too, and it must wait there for the next wake. Any
     * other stop (Home on the keyguard, another activity on top) would leave it hidden behind and
     * still counted as showing, so the next screen-off could never bring it back.
     */
    override fun onStop() {
        super.onStop()
        if (getSystemService(PowerManager::class.java)?.isInteractive == true) leave()
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        showing = false
        super.onDestroy()
    }

    /** Close asks for the unlock (PIN, fingerprint, face); the player goes once it succeeds. */
    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || keyguard?.isKeyguardLocked != true) {
            leave()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = leave()
            },
        )
    }

    private fun leave() {
        if (isFinishing) return
        showOverKeyguard(false)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    internal companion object {
        /** True while an instance exists, so a second screen-off never stacks another. */
        @Volatile
        var showing: Boolean = false
            private set
    }
}

private fun Activity.showOverKeyguard(show: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(show)
    } else {
        @Suppress("DEPRECATION")
        if (show) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }
}
