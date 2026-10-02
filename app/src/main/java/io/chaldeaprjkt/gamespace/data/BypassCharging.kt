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
 * Bypass charging through the Qualcomm charger driver: with
 * `battery/charging_enabled = 0` the charger keeps powering the device but the
 * battery is neither charged nor drained. Replaces the ROM's Health HAL based
 * implementation, which needs vendor.lineage.health (not present on GSIs).
 *
 * The node is volatile, so a reboot always restores normal charging.
 */
object BypassCharging {

    private const val NODE = "/sys/class/power_supply/battery/charging_enabled"

    /** Blocking: may prompt for root on first use. */
    fun isSupported(): Boolean = RootShell.run("test -w $NODE")

    fun setActive(active: Boolean) {
        RootShell.runAsync("[ -w $NODE ] && echo ${if (active) 0 else 1} > $NODE")
    }
}
