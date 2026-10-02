/*
 * Copyright (C) 2018 The Android Open Source Project
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

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Port of SettingsLib's com.android.settingslib.display.BrightnessUtils (the
 * HLG-based gamma curve the brightness slider uses), which is platform-only.
 */
object BrightnessUtils {

    const val GAMMA_SPACE_MIN = 0
    const val GAMMA_SPACE_MAX = 65535

    // Hybrid Log Gamma constants
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    @JvmStatic
    fun convertGammaToLinear(value: Int, min: Int, max: Int): Int {
        val normalizedRet = convertGammaToLinearNormalized(value)
        return lerp(min.toFloat(), max.toFloat(), normalizedRet / 12f).roundToInt()
    }

    @JvmStatic
    fun convertGammaToLinearFloat(value: Int, min: Float, max: Float): Float {
        val normalizedRet = convertGammaToLinearNormalized(value)
        // HLG is normalized to [0, 12]; clamp so the result stays inside [min, max].
        return lerp(min, max, normalizedRet.coerceIn(0f, 12f) / 12f)
    }

    private fun convertGammaToLinearNormalized(value: Int): Float {
        val normalizedVal = norm(GAMMA_SPACE_MIN.toFloat(), GAMMA_SPACE_MAX.toFloat(), value.toFloat())
        return if (normalizedVal <= R) {
            sq(normalizedVal / R)
        } else {
            exp((normalizedVal - C) / A) + B
        }
    }

    @JvmStatic
    fun convertLinearToGamma(value: Int, min: Int, max: Int): Int =
        convertLinearToGammaFloat(value.toFloat(), min.toFloat(), max.toFloat())

    @JvmStatic
    fun convertLinearToGammaFloat(value: Float, min: Float, max: Float): Int {
        // For some reason, HLG normalizes to the range [0, 12] rather than [0, 1]
        val normalizedVal = norm(min, max, value) * 12
        val ret = if (normalizedVal <= 1f) {
            sqrt(normalizedVal) * R
        } else {
            A * ln(normalizedVal - B) + C
        }
        return lerp(GAMMA_SPACE_MIN.toFloat(), GAMMA_SPACE_MAX.toFloat(), ret).roundToInt()
    }

    private fun lerp(start: Float, stop: Float, amount: Float) = start + (stop - start) * amount
    private fun norm(start: Float, stop: Float, value: Float) = (value - start) / (stop - start)
    private fun sq(v: Float) = v * v
}
