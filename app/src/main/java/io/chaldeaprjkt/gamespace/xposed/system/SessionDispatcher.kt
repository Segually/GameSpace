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
    private var currentGame: String? = null
    private var keyguardShowing = false

    private val sessionComponent =
        ComponentName(BridgeContract.PACKAGE, BridgeContract.SESSION_SERVICE)

    private val currentUser: UserHandle by lazy {
        XposedHelpers.getStaticObjectField(UserHandle::class.java, "CURRENT") as UserHandle
    }

    private var pendingLeave: Runnable? = null

    fun onAppFocused(
        packageName: String,
        taskOwner: String?,
        freeformTask: Boolean,
        homeOrRecents: Boolean,
    ) {
        val gameActive = currentGame?.let(isTopApp) == true
        // A freeform window over a running game doesn't end the session.
        if (freeformTask && gameActive) return
        // Neither does another app's activity running inside the game's own task.
        if (currentGame != null && taskOwner == currentGame && packageName != currentGame) return

        val isGame = packageName != BridgeContract.PACKAGE && games.isGame(packageName)
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
        if (packageName == currentGame) leaveGame()
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
        if (!games.isGame(game)) leaveGame()
    }

    private fun startSession(packageName: String) {
        val intent = Intent(BridgeContract.ACTION_GAME_START)
            .setComponent(sessionComponent)
            .putExtra(BridgeContract.EXTRA_PACKAGE_NAME, packageName)
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
    }
}
