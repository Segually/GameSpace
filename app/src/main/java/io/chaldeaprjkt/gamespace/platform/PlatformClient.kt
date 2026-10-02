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
package io.chaldeaprjkt.gamespace.platform

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.Bridges
import io.chaldeaprjkt.gamespace.bridge.IPlatformBridge
import io.chaldeaprjkt.gamespace.bridge.IPlatformCallback
import java.util.concurrent.CopyOnWriteArraySet

/**
 * App-side replacement for AxionOS' `AxPlatformClient`, keeping its API so the
 * gamebar code is unchanged. Backed by the SystemUI hook's [IPlatformBridge],
 * which drives SystemUI's own quick-settings tiles.
 */
class PlatformClient private constructor() {

    abstract class Listener {
        open fun onFeatureChanged(feature: String, active: Boolean) {}
        open fun onStateChanged(key: String, state: Bundle) {}
        open fun onSupportedFeaturesChanged(features: Set<String>) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registeredOn: IPlatformBridge? = null
    private var initialized = false

    @Volatile
    var supportedFeatures: Set<String> = emptySet()
        private set

    private val callback = object : IPlatformCallback.Stub() {
        override fun onStateChanged(feature: String, state: Bundle) {
            mainHandler.post { dispatch(feature, state) }
        }
    }

    private val bridgeListener = Bridges.Listener { mainHandler.post { reconnect() } }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        Bridges.addListener(bridgeListener)
        Bridges.request(context)
        reconnect()
    }

    fun release() {
        if (!initialized) return
        initialized = false
        Bridges.removeListener(bridgeListener)
        registeredOn?.let { bridge ->
            runCatching {
                bridge.setListening(false)
                bridge.unregisterCallback(callback)
            }
        }
        registeredOn = null
    }

    private fun reconnect() {
        val bridge = Bridges.platform
        if (bridge === registeredOn) return
        registeredOn = bridge
        if (bridge == null) {
            updateSupported(emptySet())
            return
        }
        runCatching {
            bridge.registerCallback(callback)
            bridge.setListening(true)
            updateSupported(bridge.supportedFeatures.toSet())
            supportedFeatures.forEach { feature ->
                bridge.getState(feature)?.let { dispatch(feature, it) }
            }
        }.onFailure { registeredOn = null }
    }

    private fun updateSupported(features: Set<String>) {
        if (features == supportedFeatures) return
        supportedFeatures = features
        listeners.forEach { it.onSupportedFeaturesChanged(features) }
    }

    private fun dispatch(feature: String, state: Bundle) {
        listeners.forEach {
            it.onStateChanged(feature, state)
            it.onFeatureChanged(feature, state.getBoolean(BridgeContract.STATE_ACTIVE, false))
        }
    }

    val isConnected get() = Bridges.isPlatformConnected

    fun isSupported(feature: String) = feature in supportedFeatures

    fun toggle(feature: String) {
        Bridges.withPlatform(Unit) { it.toggle(feature) }
    }

    fun getState(feature: String): Bundle =
        Bridges.withPlatform(null) { it.getState(feature) } ?: Bundle.EMPTY

    fun takeScreenshot(): Boolean =
        Bridges.withPlatform(false) { it.takeScreenshot(); true }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    companion object {
        const val FEATURE_WIFI = "wifi"
        const val FEATURE_MOBILE_DATA = "mobile_data"
        const val FEATURE_BLUETOOTH = "bluetooth"
        const val FEATURE_HOTSPOT = "hotspot"
        const val FEATURE_FLASHLIGHT = "flashlight"
        const val FEATURE_LOCATION = "location"
        const val FEATURE_ROTATION = "rotation"
        const val FEATURE_BATTERY_SAVER = "battery_saver"
        const val FEATURE_ZEN = "zen"
        const val FEATURE_AOD = "aod"
        const val FEATURE_DATA_SAVER = "data_saver"
        const val FEATURE_AIRPLANE_MODE = "airplane_mode"
        const val FEATURE_NFC = "nfc"
        const val FEATURE_DARK_MODE = "dark_mode"
        const val FEATURE_NIGHT_LIGHT = "night_light"
        const val FEATURE_COLOR_INVERSION = "color_inversion"
        const val FEATURE_COLOR_CORRECTION = "color_correction"
        const val FEATURE_REDUCE_BRIGHTNESS = "reduce_brightness"
        const val FEATURE_ONE_HANDED_MODE = "one_handed_mode"
        const val FEATURE_HEADS_UP = "heads_up"
        const val FEATURE_AUTO_SYNC = "auto_sync"
        const val FEATURE_CAMERA_PRIVACY = "camera_privacy"
        const val FEATURE_MIC_PRIVACY = "mic_privacy"
        const val FEATURE_WORK_PROFILE = "work_profile"
        const val FEATURE_USB_TETHER = "usb_tether"
        const val FEATURE_DREAM = "dream"
        const val FEATURE_READING_MODE = "reading_mode"
        const val FEATURE_POWER_SHARE = "power_share"
        const val FEATURE_CAFFEINE = "caffeine"
        const val FEATURE_VPN = "vpn"
        const val FEATURE_CAST = "cast"
        const val FEATURE_PROFILES = "profiles"
        const val FEATURE_SMART_PIXELS = "smart_pixels"
        const val FEATURE_SCREEN_RECORD = "screen_record"
        const val FEATURE_SCREENSHOT = "screenshot"

        /**
         * GameSpace feature id -> LineageOS QS tile spec. Features without an
         * entry have no LineageOS tile and are never reported as supported.
         */
        val FEATURE_TO_TILE_SPEC = mapOf(
            FEATURE_WIFI to "internet",
            FEATURE_MOBILE_DATA to "cell",
            FEATURE_BLUETOOTH to "bt",
            FEATURE_HOTSPOT to "hotspot",
            FEATURE_FLASHLIGHT to "flashlight",
            FEATURE_LOCATION to "location",
            FEATURE_ROTATION to "rotation",
            FEATURE_BATTERY_SAVER to "battery",
            FEATURE_ZEN to "dnd",
            FEATURE_AOD to "ambient_display",
            FEATURE_DATA_SAVER to "saver",
            FEATURE_AIRPLANE_MODE to "airplane",
            FEATURE_NFC to "nfc",
            FEATURE_DARK_MODE to "dark",
            FEATURE_NIGHT_LIGHT to "night",
            FEATURE_COLOR_INVERSION to "inversion",
            FEATURE_COLOR_CORRECTION to "color_correction",
            FEATURE_REDUCE_BRIGHTNESS to "reduce_brightness",
            FEATURE_ONE_HANDED_MODE to "onehanded",
            FEATURE_HEADS_UP to "heads_up",
            FEATURE_AUTO_SYNC to "sync",
            FEATURE_CAMERA_PRIVACY to "cameratoggle",
            FEATURE_MIC_PRIVACY to "mictoggle",
            FEATURE_WORK_PROFILE to "work",
            FEATURE_USB_TETHER to "usb_tether",
            FEATURE_DREAM to "dream",
            FEATURE_READING_MODE to "reading_mode",
            FEATURE_CAFFEINE to "caffeine",
            FEATURE_VPN to "vpn",
            FEATURE_CAST to "cast",
            FEATURE_PROFILES to "profiles",
            FEATURE_SCREEN_RECORD to "screenrecord",
        )

        const val TILE_STATE_UNAVAILABLE = 0
        const val TILE_STATE_INACTIVE = 1
        const val TILE_STATE_ACTIVE = 2

        @JvmStatic
        fun getTileState(state: Bundle): Int =
            state.getInt(BridgeContract.STATE_TILE_STATE, TILE_STATE_UNAVAILABLE)

        @JvmStatic
        fun getLabel(state: Bundle): String? = state.getString(BridgeContract.STATE_LABEL)

        @JvmStatic
        fun getSecondaryLabel(state: Bundle): String? =
            state.getString(BridgeContract.STATE_SECONDARY_LABEL)

        @Volatile
        private var instance: PlatformClient? = null

        @JvmStatic
        fun getInstance(): PlatformClient =
            instance ?: synchronized(this) { instance ?: PlatformClient().also { instance = it } }
    }
}
