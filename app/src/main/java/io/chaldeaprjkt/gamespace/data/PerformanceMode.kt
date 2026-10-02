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

import io.chaldeaprjkt.gamespace.root.RootShell

/**
 * System-wide performance boost, standing in for the ROM's perf-mode prop:
 *  - Power HAL fixed performance mode (`cmd power`), when the HAL supports it
 *  - Touch controller game mode (`/proc/game_mode`, Lenovo/Novatek panels),
 *    which the stock firmware enabled from the hardware gaming switch
 *
 * Both are volatile: a reboot always returns to normal.
 */
object PerformanceMode {

    private const val TOUCH_GAME_MODE = "/proc/game_mode"

    fun setActive(active: Boolean) {
        RootShell.runAsync(
            "cmd power set-fixed-performance-mode-enabled $active",
            "[ -w $TOUCH_GAME_MODE ] && echo ${if (active) 1 else 0} > $TOUCH_GAME_MODE",
        )
    }
}
