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

import android.content.Context
import android.database.ContentObserver
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.xposed.XLog

/**
 * system_server-side copy of the app's game list (replaces the ROM's
 * GameListManager). Loaded from GameSpaceProvider and refreshed when the app
 * notifies [BridgeContract.GAMES_URI].
 */
class GameListCache(private val context: Context, private val handler: Handler) {

    @Volatile
    private var games: Map<String, Int> = emptyMap()

    @Volatile
    private var denied: Set<String> = emptySet()

    @Volatile
    var autoDetect = true
        private set

    @Volatile
    private var loaded = false
    private var lastAttempt = 0L

    var onChanged: (() -> Unit)? = null

    fun start() {
        runCatching {
            context.contentResolver.registerContentObserver(
                BridgeContract.GAMES_URI, false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        XLog.guard("game list changed") {
                            reload()
                            onChanged?.invoke()
                        }
                    }
                }
            )
        }.onFailure { XLog.w("Cannot observe game list", it) }
    }

    fun isGame(packageName: String): Boolean {
        ensureLoaded()
        return packageName in games
    }

    fun isDenied(packageName: String): Boolean {
        ensureLoaded()
        return packageName in denied
    }

    private fun ensureLoaded() {
        if (loaded) return
        val now = SystemClock.uptimeMillis()
        if (lastAttempt != 0L && now - lastAttempt < RETRY_INTERVAL_MS) return
        lastAttempt = now
        reload()
    }

    fun modeOf(packageName: String): Int? = games[packageName]

    /** Must be called off the WM lock (it may start the GameSpace process). */
    fun reload() {
        val state = call(BridgeContract.METHOD_GET_STATE, null) ?: return
        games = (state.getString(BridgeContract.KEY_GAME_LIST) ?: "")
            .split(";")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                val pkg = parts[0].trim()
                if (pkg.isEmpty()) null else pkg to (parts.getOrNull(1)?.toIntOrNull() ?: 1)
            }
            .toMap()
        denied = state.getStringArray(BridgeContract.KEY_DENIED_LIST)?.toSet() ?: emptySet()
        autoDetect = state.getBoolean(BridgeContract.KEY_AUTO_DETECT, true)
        loaded = true
    }

    fun call(method: String, arg: String?, extras: Bundle? = null): Bundle? {
        val token = Binder.clearCallingIdentity()
        return try {
            context.contentResolver.call(BridgeContract.AUTHORITY_URI, method, arg, extras)
        } catch (t: Throwable) {
            // GameSpace not installed / not yet unlocked; try again on the next event.
            XLog.w("Provider call $method failed: ${t.message}")
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 30_000L
    }
}
