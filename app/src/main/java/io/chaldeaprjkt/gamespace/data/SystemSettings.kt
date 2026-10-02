/*
 * Copyright (C) 2021 Chaldeaprjkt
 *               2022-2026 crDroid Android Project
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
package io.chaldeaprjkt.gamespace.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.Bridges
import io.chaldeaprjkt.gamespace.utils.GameModeUtils
import javax.inject.Inject

/**
 * System-side toggles GameSpace flips during a session. Reads use the public
 * Settings API (the app runs as the current user); writes go through the
 * system_server bridge with a root fallback, see [Bridges.putSetting].
 */
class SystemSettings @Inject constructor(
    context: Context,
    private val gameModeUtils: GameModeUtils
) {

    private val resolver = context.contentResolver
    private val gameStore = GameStore(context)

    private val wakelock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.FULL_WAKE_LOCK, "GameSpace")

    var headsup
        get() = Settings.Global.getInt(resolver, KEY_HEADS_UP, 1) == 1
        set(it) {
            Bridges.putSetting(BridgeContract.TABLE_GLOBAL, KEY_HEADS_UP, it.toInt().toString())
        }

    var autoBrightness
        get() = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        set(auto) {
            Bridges.putSetting(
                BridgeContract.TABLE_SYSTEM,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                (if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL).toString()
            )
        }

    /** null when this LineageOS build has no status bar brightness control (23.2+). */
    var statusbarBrightness: Boolean?
        get() = getLineageString(KEY_STATUS_BAR_BRIGHTNESS)?.let { it == "1" }
        set(value) {
            if (value == null || statusbarBrightness == null) return
            Bridges.putSetting(
                BridgeContract.TABLE_LINEAGE_SYSTEM, KEY_STATUS_BAR_BRIGHTNESS,
                value.toInt().toString()
            )
        }

    var stayAwake: Boolean
        get() = wakelock.isHeld
        @SuppressLint("WakelockTimeout")
        set(enable) {
            if (enable) {
                if (!wakelock.isHeld) wakelock.acquire()
            } else {
                if (wakelock.isHeld) wakelock.release()
            }
        }

    var threeScreenshot
        get() = getLineageInt(KEY_THREE_FINGERS_SWIPE, 0)
        set(value) {
            Bridges.putSetting(
                BridgeContract.TABLE_LINEAGE_SYSTEM, KEY_THREE_FINGERS_SWIPE, value.toString()
            )
        }

    var userGames
        get() = gameStore.games
        set(games) {
            gameStore.games = games
            gameModeUtils.setupBatteryMode(games.isNotEmpty())
        }

    var autoGameDetect
        get() = gameStore.autoDetect
        set(value) {
            gameStore.autoDetect = value
        }

    var deniedGames
        get() = gameStore.deniedList
        set(value) {
            gameStore.deniedList = value
        }

    /** LineageSettings.System read without linking against the Lineage SDK. */
    private fun getLineageString(key: String): String? = runCatching {
        resolver.call(LINEAGE_SETTINGS_URI, "GET_system", key, null)?.getString("value")
    }.getOrNull()

    private fun getLineageInt(key: String, default: Int): Int =
        getLineageString(key)?.toIntOrNull() ?: default

    private fun Boolean.toInt() = if (this) 1 else 0

    private companion object {
        val LINEAGE_SETTINGS_URI: Uri = Uri.parse("content://lineagesettings")
        const val KEY_HEADS_UP = "heads_up_notifications_enabled"
        const val KEY_STATUS_BAR_BRIGHTNESS = "status_bar_brightness_control"
        const val KEY_THREE_FINGERS_SWIPE = "three_fingers_swipe"
    }
}
