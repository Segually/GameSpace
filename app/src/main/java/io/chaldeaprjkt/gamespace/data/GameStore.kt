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
package io.chaldeaprjkt.gamespace.data

import android.app.GameManager
import android.content.Context
import android.content.SharedPreferences
import io.chaldeaprjkt.gamespace.bridge.BridgeContract

/**
 * Game list, denied list and auto-detect flag. These used to live in
 * Settings.System (written by a platform-signed app); they are now app data,
 * served to the system_server hook through GameSpaceProvider.
 *
 * Stored in device-protected storage so the hook can read it before unlock.
 */
class GameStore(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val prefs: SharedPreferences = appContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Raw `pkg=mode;pkg=mode` string, the same format the ROM framework used. */
    var rawGameList: String
        get() = prefs.getString(KEY_GAME_LIST, "") ?: ""
        set(value) = commitAndNotify { putString(KEY_GAME_LIST, value) }

    var games: List<UserGame>
        get() = rawGameList.split(";")
            .filter { it.isNotBlank() }
            .map { UserGame.fromSettings(it) }
        set(value) {
            rawGameList = value.joinToString(";") { it.toString() }
        }

    var deniedList: Set<String>
        get() = (prefs.getString(KEY_DENIED_LIST, "") ?: "")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(value) = commitAndNotify { putString(KEY_DENIED_LIST, value.joinToString(";")) }

    var autoDetect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT, true)
        set(value) = commitAndNotify { putBoolean(KEY_AUTO_DETECT, value) }

    fun isGame(packageName: String) = games.any { it.packageName == packageName }

    /** Used by auto-detect: adds in performance mode, like GameListManager did. */
    fun addGame(packageName: String): Boolean {
        if (isGame(packageName) || packageName in deniedList) return false
        games = games + UserGame(packageName, GameManager.GAME_MODE_PERFORMANCE)
        return true
    }

    fun removeGame(packageName: String): Boolean {
        val current = games
        if (current.none { it.packageName == packageName }) return false
        games = current.filter { it.packageName != packageName }
        return true
    }

    private inline fun commitAndNotify(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).commit()
        appContext.contentResolver.notifyChange(BridgeContract.GAMES_URI, null)
    }

    companion object {
        private const val PREFS_NAME = "game_store"
        private const val KEY_GAME_LIST = "game_list"
        private const val KEY_DENIED_LIST = "denied_list"
        private const val KEY_AUTO_DETECT = "auto_detect"
    }
}
