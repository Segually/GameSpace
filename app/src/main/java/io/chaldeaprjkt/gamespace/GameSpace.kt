/*
 * Copyright (C) 2021 Chaldeaprjkt
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
package io.chaldeaprjkt.gamespace

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.chaldeaprjkt.gamespace.bridge.Bridges
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.BypassCharging
import io.chaldeaprjkt.gamespace.data.PerformanceMode
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp(Application::class)
class GameSpace : Hilt_GameSpace() {

    private val TAG = "GameSpace"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
        // The mapper and a few overlay helpers still poke @hide framework APIs.
        HiddenApiBypass.addHiddenApiExemptions("")
        Bridges.request(this)
        // If a previous process died mid-session, don't leave charging paused.
        val settings = AppSettings(this)
        if (settings.bypassCharge) BypassCharging.setActive(false)
        if (settings.performanceBoost) PerformanceMode.setActive(false)
    }
}
