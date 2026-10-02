/*
 * Copyright (C) 2026 GameSpace contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.xposed.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import de.robv.android.xposed.XposedHelpers
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.xposed.XLog

/**
 * Port of the ROM's GameSpaceService/GameStateDispatcher: decides when a
 * registered game is in front and starts/stops GameSpace's SessionService.
 * All methods run on [handler]'s thread.
 */
class SessionDispatcher(
    private val context: Context,
    private val handler: Handler,
    private val games: GameListCache,
    private val bridge: SystemBridgeService,
    private val isTopApp: (String) -> Boolean,
    private val onSessionChanged: (active: Boolean) -> Unit,
) {
    /** Package of the running session, or [BridgeContract.GLOBAL_SESSION]. */
    private var currentGame: String? = null
    private var keyguardShowing = false

    // Hardware gaming switch state
    private var globalMode = false
    private var forcedApp: String? = null
    private var switchRestored = false

    // Last focused app, so switch actions can act on "whatever is in front"
    private var lastFocused: String? = null
    private var lastFocusedHome = false

    private val sessionComponent =
        ComponentName(BridgeContract.PACKAGE, BridgeContract.SESSION_SERVICE)

    private val currentUser: UserHandle by lazy {
        XposedHelpers.getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle
    }

    private var pendingLeave: Runnable? = null

    /** False only when the switch is the master switch and it's off. */
    private val gameSpaceEnabled
        get() = !(games.switchAction == BridgeContract.SWITCH_MASTER && games.switchOn == false)

    private fun isTarget(packageName: String) =
        packageName != BridgeContract.PACKAGE && gameSpaceEnabled &&
            (packageName == forcedApp || games.isGame(packageName))

    fun onAppFocused(
        packageName: String,
        taskOwner: String?,
        freeformTask: Boolean,
        homeOrRecents: Boolean,
    ) {
        lastFocused = packageName
        lastFocusedHome = homeOrRecents
        restoreSwitchOnce()

        if (globalMode) {
            // One session for everything: keep the FPS counter on the focused app and
            // still honour per-game modes for listed games.
            bridge.onFocusedTaskChanged()
            games.modeOf(packageName)?.let { bridge.applyGameMode(packageName, it) }
            return
        }

        val gameActive = currentGame?.let(isTopApp) == true
        // A freeform window over a running game doesn't end the session.
        if (freeformTask && gameActive) return
        // Neither does another app's activity running inside the game's own task.
        if (currentGame != null && taskOwner == currentGame && packageName != currentGame) return

        val isGame = isTarget(packageName)
        when {
            isGame && packageName != currentGame -> {
                cancelPendingLeave()
                if (currentGame != null) stopSession()
                currentGame = packageName
                if (!keyguardShowing) startSession(packageName)
            }
            isGame -> {
                cancelPendingLeave()
                bridge.onFocusedTaskChanged()
            }
            !isGame && currentGame != null -> {
                if (homeOrRecents) {
                    leaveGame()
                } else if (pendingLeave == null) {
                    // Sign-in / permission popups from other apps come and go quickly;
                    // only end the session if the game doesn't come back.
                    val leave = Runnable {
                        pendingLeave = null
                        leaveGame()
                    }
                    pendingLeave = leave
                    handler.postDelayed(leave, LEAVE_DELAY_MS)
                }
            }
        }
    }

    /** Hardware gaming switch (Lenovo TB-9707F `game_mode_switcher`). */
    fun onGameSwitch(on: Boolean) {
        // Persists the position and shows feedback in the app; returns the configured action.
        val action = games.call(BridgeContract.METHOD_SWITCH_CHANGED, if (on) "1" else "0")
            ?.getString(BridgeContract.KEY_SWITCH_ACTION)
            ?: games.switchAction
        games.switchAction = action
        games.switchOn = on
        switchRestored = true
        XLog.i("Gaming switch ${if (on) "on" else "off"} ($action)")
        applySwitch(on, action)
    }

    private fun applySwitch(on: Boolean, action: String) {
        if (!on) {
            // Off always undoes whatever on did, even if the action was changed meanwhile.
            if (globalMode) setGlobalMode(false)
            forcedApp?.let { forced ->
                forcedApp = null
                if (currentGame == forced && !games.isGame(forced)) leaveGame()
            }
            if (action == BridgeContract.SWITCH_MASTER) leaveGame()
            return
        }
        when (action) {
            BridgeContract.SWITCH_SYSTEM_WIDE -> setGlobalMode(true)
            BridgeContract.SWITCH_CURRENT_APP -> {
                val app = lastFocused?.takeIf { !lastFocusedHome && it != BridgeContract.PACKAGE }
                    ?: return
                forcedApp = app
                reevaluateFocus()
            }
            BridgeContract.SWITCH_MASTER -> reevaluateFocus()
        }
    }

    /** After boot, bring back the system-wide mode if the switch was left on. */
    private fun restoreSwitchOnce() {
        if (switchRestored || !games.isLoaded) return
        switchRestored = true
        if (games.switchOn == true && games.switchAction == BridgeContract.SWITCH_SYSTEM_WIDE) {
            setGlobalMode(true)
        }
    }

    private fun setGlobalMode(enabled: Boolean) {
        if (enabled == globalMode) return
        cancelPendingLeave()
        if (enabled) {
            if (currentGame != null) stopSession()
            globalMode = true
            currentGame = BridgeContract.GLOBAL_SESSION
            if (!keyguardShowing) startSession(BridgeContract.GLOBAL_SESSION)
        } else {
            globalMode = false
            if (currentGame == BridgeContract.GLOBAL_SESSION) {
                currentGame = null
                stopSession()
            }
            reevaluateFocus()
        }
    }

    private fun reevaluateFocus() {
        val pkg = lastFocused ?: return
        onAppFocused(pkg, null, false, lastFocusedHome)
    }

    private fun leaveGame() {
        cancelPendingLeave()
        if (currentGame == null) return
        currentGame = null
        stopSession()
    }

    private fun cancelPendingLeave() {
        pendingLeave?.let { handler.removeCallbacks(it) }
        pendingLeave = null
    }

    fun onTaskRemoved(packageName: String) {
        if (packageName == forcedApp) forcedApp = null
        if (!globalMode && packageName == currentGame) leaveGame()
    }

    fun onKeyguardChanged(showing: Boolean) {
        if (showing == keyguardShowing) return
        keyguardShowing = showing
        cancelPendingLeave()
        val game = currentGame ?: return
        if (showing) stopSession() else startSession(game)
    }

    /** The game list changed: end the session if the current game was removed. */
    fun onGameListChanged() {
        val game = currentGame ?: return
        if (globalMode || game == forcedApp) return
        if (!games.isGame(game)) leaveGame()
    }

    private fun startSession(packageName: String) {
        val intent = Intent(BridgeContract.ACTION_GAME_START)
            .setComponent(sessionComponent)
            .putExtra(BridgeContract.EXTRA_PACKAGE_NAME, packageName)
            // Boost for system-wide mode and for games set to the Performance game mode
            .putExtra(
                BridgeContract.EXTRA_PERFORMANCE,
                packageName == BridgeContract.GLOBAL_SESSION ||
                    games.modeOf(packageName) == GAME_MODE_PERFORMANCE
            )
            .putExtra(
                BridgeContract.EXTRA_SYSTEM_BRIDGE,
                Bundle().apply { putBinder(BridgeContract.KEY_BINDER, bridge) }
            )
        withSystemIdentity {
            runCatching {
                XposedHelpers.callMethod(
                    context, "startForegroundServiceAsUser",
                    arrayOf(Intent::class.java, UserHandle::class.java), intent, currentUser
                )
                XLog.i("Game session started for $packageName")
                onSessionChanged(true)
            }.onFailure { XLog.w("Cannot start GameSpace session", it) }
        }
    }

    private fun stopSession() {
        onSessionChanged(false)
        withSystemIdentity {
            runCatching {
                XposedHelpers.callMethod(
                    context, "stopServiceAsUser",
                    arrayOf(Intent::class.java, UserHandle::class.java),
                    Intent().setComponent(sessionComponent), currentUser
                )
                XLog.i("Game session stopped")
            }.onFailure { XLog.w("Cannot stop GameSpace session", it) }
        }
    }

    private inline fun withSystemIdentity(block: () -> Unit) {
        val token = Binder.clearCallingIdentity()
        try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun post(block: SessionDispatcher.() -> Unit) {
        handler.post { XLog.guard("session dispatch") { block() } }
    }

    private companion object {
        const val LEAVE_DELAY_MS = 1500L
        const val GAME_MODE_PERFORMANCE = 2
    }
}
