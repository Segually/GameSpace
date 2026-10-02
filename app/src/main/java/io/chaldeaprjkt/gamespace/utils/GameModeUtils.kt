/*
 * Copyright (C) 2021 Chaldeaprjkt
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
package io.chaldeaprjkt.gamespace.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.app.GameManager
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.Bridges
import io.chaldeaprjkt.gamespace.root.RootShell
import io.chaldeaprjkt.gamespace.data.GameConfig
import io.chaldeaprjkt.gamespace.data.GameConfig.Companion.asConfig
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.data.UserGame
import javax.inject.Inject

class GameModeUtils @Inject constructor(private val context: Context) {

    var activeGame: UserGame? = null

    /**
     * Publish per-mode interventions (downscale/fps) for [packageName] through the
     * stock `game_overlay` DeviceConfig namespace that GameManagerService reads,
     * e.g. `mode=2,downscaleFactor=0.7:mode=3,downscaleFactor=0.8`.
     */
    fun setIntervention(packageName: String, modeData: List<GameConfig>? = null) {
        val config = modeData?.asConfig()
        if (Bridges.isSystemConnected) {
            Bridges.withSystem(Unit) { it.setGameIntervention(packageName, config) }
            return
        }
        val pkg = RootShell.quote(packageName)
        if (config == null) {
            RootShell.runAsync("device_config delete game_overlay $pkg")
        } else {
            RootShell.runAsync("device_config put game_overlay $pkg ${RootShell.quote(config)}")
        }
    }

    fun applyGameMode(packageName: String, mode: Int) {
        if (Bridges.isSystemConnected) {
            Bridges.withSystem(Unit) { it.setGameMode(packageName, mode) }
            return
        }
        val name = when (mode) {
            GameManager.GAME_MODE_PERFORMANCE -> "performance"
            GameManager.GAME_MODE_BATTERY -> "battery"
            GameManager.GAME_MODE_CUSTOM -> "custom"
            else -> "standard"
        }
        RootShell.runAsync("cmd game mode $name ${RootShell.quote(packageName)}")
    }

    fun getAvailableGameModes(packageName: String): IntArray =
        Bridges.withSystem(null) { it.getAvailableGameModes(packageName) }
            ?: intArrayOf(
                GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY,
            )

    fun setActiveGameMode(systemSettings: SystemSettings, mode: Int) {
        val packageName = activeGame?.packageName ?: return
        applyGameMode(packageName, mode)
        activeGame = setGameModeFor(packageName, systemSettings, mode)
    }

    fun setGameModeFor(packageName: String, systemSettings: SystemSettings, mode: Int): UserGame {
        val data = UserGame(packageName, mode)
        systemSettings.userGames = systemSettings.userGames
            .filter { x -> x.packageName != packageName }
            .toMutableList()
            .apply { add(data) }

        return data
    }

    /**
     * Keep GameSpace on the doze whitelist while games are registered, so the
     * session service can be started from the background when a game launches.
     */
    fun setupBatteryMode(enable: Boolean) {
        val op = if (enable) "+" else "-"
        RootShell.runAsync("dumpsys deviceidle whitelist $op${context.packageName}")
    }


    fun findAnglePackage(): ActivityInfo? {
        val intent = Intent(ACTION_ANGLE_FOR_ANDROID)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())
        val info = context.packageManager.queryIntentActivities(intent, flags)
        return info.firstOrNull()?.activityInfo
    }

    fun getAngleDriverChoice(packageName: String): String {
        val resolver = context.contentResolver
        val pkgsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_PACKAGES)
            ?: return DRIVER_CHOICE_DEFAULT
        val valsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_VALUES)
            ?: return DRIVER_CHOICE_DEFAULT
        val pkgs = pkgsStr.split(",")
        val vals = valsStr.split(",")
        val index = pkgs.indexOf(packageName)
        if (index < 0 || index >= vals.size) return DRIVER_CHOICE_DEFAULT
        return vals[index]
    }

    fun setAngleDriverChoice(packageName: String, choice: String) {
        val resolver = context.contentResolver
        val pkgsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_PACKAGES) ?: ""
        val valsStr = Settings.Global.getString(resolver, DRIVER_SELECTION_VALUES) ?: ""
        val pkgs = if (pkgsStr.isEmpty()) mutableListOf()
            else pkgsStr.split(",").toMutableList()
        val vals = if (valsStr.isEmpty()) mutableListOf()
            else valsStr.split(",").toMutableList()

        val index = pkgs.indexOf(packageName)
        if (choice == DRIVER_CHOICE_DEFAULT) {
            if (index >= 0) {
                pkgs.removeAt(index)
                vals.removeAt(index)
            }
        } else {
            if (index >= 0) {
                vals[index] = choice
            } else {
                pkgs.add(packageName)
                vals.add(choice)
            }
        }

        Bridges.putSetting(
            BridgeContract.TABLE_GLOBAL, DRIVER_SELECTION_PACKAGES, pkgs.joinToString(",")
        )
        Bridges.putSetting(
            BridgeContract.TABLE_GLOBAL, DRIVER_SELECTION_VALUES, vals.joinToString(",")
        )
    }

    fun isVulkanSupported(): Boolean =
        context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0
        )

    companion object {
        const val defaultPreferredMode = GameManager.GAME_MODE_STANDARD
        const val ACTION_ANGLE_FOR_ANDROID = "android.app.action.ANGLE_FOR_ANDROID"
        private const val DRIVER_SELECTION_PACKAGES = "angle_gl_driver_selection_pkgs"
        private const val DRIVER_SELECTION_VALUES = "angle_gl_driver_selection_values"
        const val DRIVER_CHOICE_DEFAULT = "default"
        const val DRIVER_CHOICE_ANGLE = "angle"
        const val DRIVER_CHOICE_NATIVE = "native"
        private const val VULKAN_1_0 = 0x00400000

        fun Context.describeGameMode(mode: Int) =
            resources.getStringArray(R.array.game_mode_names)[mode] ?: resources.getString(R.string.game_mode_unsupported)
    }
}
