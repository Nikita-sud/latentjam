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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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
    val trigger = lockPlayerTrigger
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> trigger.onScreenOn(
                    keyguardLocked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
                )
                Intent.ACTION_USER_PRESENT -> trigger.onUnlocked()
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
        addAction(Intent.ACTION_USER_PRESENT)
    }
    // Main: the receiver runs there too, so the trigger is only ever touched from one thread.
    AppGraph.appScope.launch(Dispatchers.Main) {
        var registered = false
        AppGraph.settings.lockScreenPlayer.collect { enabled ->
            if (enabled && !registered) {
                // Exported: System UI, an app uid, sends USER_PRESENT; a not-exported receiver never
                // hears it. All three actions are protected broadcasts only the system may send.
                ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            } else if (!enabled && registered) {
                app.unregisterReceiver(receiver)
                registered = false
                trigger.onScreenOn()
                trigger.onUnlocked()
            }
        }
    }
}

/** One per process; touched only on the main thread (receiver, activity, settings collector). */
private val lockPlayerTrigger = LockPlayerTrigger()

/**
 * The full player above the keyguard, edge to edge. It drives the same playback controller as the
 * app, so it always agrees with the media notification. It leaves when the device is unlocked, when
 * playback stops or ends while the screen is off ([lockPlayerShouldLeave]), and when it wakes with
 * no keyguard to sit on; its close action asks the system to unlock rather than dropping back to
 * the lock screen.
 */
class LockScreenPlayerActivity : ComponentActivity() {

    /** An unlock was asked for and has not finished; the keyguard check at wake waits for it. */
    private var unlocking = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) leave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        showOverKeyguard(true)
        // Edge to edge with transparent bars, and into the short-edge cutout like a full-screen
        // media screen; the stored theme then tints the bar icons.
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyStoredWindowTheme()
        // The stored theme paints an opaque floor; this window stays see-through (its theme), so a
        // swipe down uncovers the lock screen rather than a blank colour.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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
                // Below STARTED the screen is off: a stop that happens then (sleep timer, end of
                // queue, focus loss, unplugged headphones) closes it, so the next wake shows the
                // plain lock screen.
                AppGraph.playback.state
                    .map { (it.track == null) to lockPlayerKeepsWhileAsleep(it) }
                    .distinctUntilChanged()
                    .collectLatest {
                        if (lockPlayerLeavesAfterGrace({ AppGraph.playback.state.value }, ::asleep)) leave()
                    }
            }
        }
        setContent {
            LockScreenPlayer(
                playback = AppGraph.playback,
                onClose = { unlock() },
                onOpenApp = { unlock(then = ::openPlayerInApp) },
                onDismiss = {
                    lockPlayerTrigger.onDismissed()
                    leave()
                },
            )
        }
    }

    /**
     * The screen going off stops the player too, and it must wait there for the next wake. Any
     * other stop (Home on the keyguard, another activity on top) would leave it hidden behind and
     * still counted as showing, so the next screen-off could never bring it back.
     */
    override fun onStop() {
        super.onStop()
        // Asleep it stays only while playback is meant to run; a pause pressed on it while the
        // screen was on ends here, at the next screen-off.
        if (screenOn() || lockPlayerShouldLeave(AppGraph.playback.state.value, asleep = true)) leave()
    }

    override fun onStart() {
        super.onStart()
        leaveWithoutKeyguard()
    }

    override fun onResume() {
        super.onResume()
        leaveWithoutKeyguard()
    }

    /**
     * Woken with no keyguard (lock "None", or within the lock-after-timeout grace): there is no lock
     * screen to sit on, so it must not stay as an ordinary screen on an unlocked phone. The keyguard
     * counts as locked while this player hides it, so the normal path is unaffected. Checked only
     * with the screen on: at screen-off the keyguard may not be shown yet.
     */
    private fun leaveWithoutKeyguard() {
        if (unlocking || !screenOn()) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) leave()
    }

    private fun screenOn(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun asleep(): Boolean = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        showing = false
        super.onDestroy()
    }

    /**
     * Asks for the unlock (PIN, fingerprint, face); once it succeeds, [then] runs and the player
     * goes. A cancelled unlock leaves the player where it was.
     */
    private fun unlock(then: () -> Unit = {}) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || keyguard?.isKeyguardLocked != true) {
            then()
            leave()
            return
        }
        unlocking = true
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    unlocking = false
                    then()
                    leave()
                }

                override fun onDismissCancelled() {
                    unlocking = false
                }

                override fun onDismissError() {
                    unlocking = false
                }
            },
        )
    }

    /** The lock screen as a way in: the app comes forward with its full player open. */
    private fun openPlayerInApp() {
        PlayerOpenRequest.pending.value = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
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
