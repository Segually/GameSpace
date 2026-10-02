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
import android.view.KeyEvent
import io.chaldeaprjkt.gamespace.platform.PlatformClient
import io.chaldeaprjkt.gamespace.root.RootShell
import javax.inject.Inject

class ScreenUtils @Inject constructor(private val context: Context) {

    /**
     * Take a full screenshot through SystemUI (via the SystemUI hook), or by
     * injecting KEYCODE_SYSRQ as root when the hook is unavailable.
     */
    fun takeScreenshot() {
        if (PlatformClient.getInstance().takeScreenshot()) return
        RootShell.runAsync("input keyevent ${KeyEvent.KEYCODE_SYSRQ}")
    }
}
