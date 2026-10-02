/*
 * Copyright (C) 2025 AxionOS
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
package io.chaldeaprjkt.gamespace.gamebar.brightness

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.Bridges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.chaldeaprjkt.gamespace.utils.BrightnessUtils.GAMMA_SPACE_MAX
import io.chaldeaprjkt.gamespace.utils.BrightnessUtils.GAMMA_SPACE_MIN
import io.chaldeaprjkt.gamespace.utils.BrightnessUtils.convertGammaToLinearFloat
import io.chaldeaprjkt.gamespace.utils.BrightnessUtils.convertLinearToGammaFloat
import javax.inject.Inject
import javax.inject.Singleton

/** Linear-space brightness of the default display, as reported by DisplayManager. */
data class BrightnessInfo(
    val brightness: Float,
    val brightnessMinimum: Float,
    val brightnessMaximum: Float,
)

@Singleton
class BrightnessRepository @Inject constructor(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    private val _brightnessInfo = MutableStateFlow<BrightnessInfo?>(null)
    val brightnessInfo: StateFlow<BrightnessInfo?> = _brightnessInfo

    private val _isAuto = MutableStateFlow<Boolean?>(null)
    val isAuto: StateFlow<Boolean?> = _isAuto
    
    private var observerRegistered = false
    private var observer: ContentObserver? = null

    fun refresh() {
        _isAuto.value = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        _brightnessInfo.value = Bridges.withSystem(null) { it.brightnessInfo }
            ?.takeIf { it.size == 3 }
            ?.let { BrightnessInfo(it[0], it[1], it[2]) }
    }

    fun setBrightness(percent: Float) {
        _brightnessInfo.value?.let { info ->
            val gamma = GAMMA_SPACE_MIN + percent * (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN)
            val linear = convertGammaToLinearFloat(
                gamma.toInt(),
                info.brightnessMinimum,
                info.brightnessMaximum
            ).coerceIn(0f, 1f)

            Bridges.withSystem(Unit) { it.setBrightness(linear) }
        }
    }

    fun setAutoMode(enabled: Boolean) {
        Bridges.putSetting(
            BridgeContract.TABLE_SYSTEM,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            (if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL).toString()
        )
        refresh()
    }

    fun start() {
        if (observerRegistered) return
        observerRegistered = true
        observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false, observer!!
        )

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false, observer!!
        )
        refresh()
    }
    
    fun dispose() {
        if (!observerRegistered || observer == null) return
        observerRegistered = false
        contentResolver.unregisterContentObserver(observer!!)
    }
}
