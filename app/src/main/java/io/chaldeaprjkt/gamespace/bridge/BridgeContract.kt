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
package io.chaldeaprjkt.gamespace.bridge

import android.net.Uri

/**
 * Names shared between the app and the Xposed hooks. The hooks run inside
 * system_server / SystemUI but load these classes from the same APK.
 */
object BridgeContract {
    const val PACKAGE = "io.chaldeaprjkt.gamespace"
    const val AUTHORITY = "$PACKAGE.provider"
    val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")
    val GAMES_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "games")

    const val VERSION = 1

    // Provider call() methods
    const val METHOD_ATTACH_SYSTEM = "attach_system"
    const val METHOD_ATTACH_SYSTEMUI = "attach_systemui"
    const val METHOD_GET_STATE = "get_state"
    const val METHOD_ADD_GAME = "add_game"
    const val METHOD_REMOVE_GAME = "remove_game"
    /** system_server -> app: the hardware gaming switch moved (arg "1"/"0"); returns KEY_SWITCH_ACTION. */
    const val METHOD_SWITCH_CHANGED = "switch_changed"

    // Bundle keys
    const val KEY_BINDER = "binder"
    const val KEY_GAME_LIST = "game_list"
    const val KEY_DENIED_LIST = "denied_list"
    const val KEY_AUTO_DETECT = "auto_detect"
    const val KEY_RESULT = "result"
    const val KEY_SWITCH_ACTION = "switch_action"
    const val KEY_SWITCH_ON = "switch_on"

    // What the hardware gaming switch (e.g. Lenovo TB-9707F) does
    const val SWITCH_SYSTEM_WIDE = "system_wide"
    const val SWITCH_CURRENT_APP = "current_app"
    const val SWITCH_MASTER = "master"
    const val SWITCH_NONE = "none"

    /** SessionService package name for a session that isn't tied to one game. */
    const val GLOBAL_SESSION = "*system_wide*"

    /** Sent by the app when it needs the hooks to (re)attach their binders. */
    const val ACTION_REQUEST_BRIDGE = "$PACKAGE.action.REQUEST_BRIDGE"

    // SessionService start intent
    const val ACTION_GAME_START = "game_start"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_SYSTEM_BRIDGE = "system_bridge"
    const val SESSION_SERVICE = "$PACKAGE.gamebar.SessionService"
    const val EXTRA_PERFORMANCE = "performance"

    // Settings tables understood by ISystemBridge.putSetting
    const val TABLE_SYSTEM = "system"
    const val TABLE_SECURE = "secure"
    const val TABLE_GLOBAL = "global"
    const val TABLE_LINEAGE_SYSTEM = "lineage_system"

    /** Keys the system bridge will write; anything else is rejected. */
    val WRITABLE_SETTINGS = setOf(
        "$TABLE_GLOBAL/heads_up_notifications_enabled",
        "$TABLE_GLOBAL/angle_gl_driver_selection_pkgs",
        "$TABLE_GLOBAL/angle_gl_driver_selection_values",
        "$TABLE_SYSTEM/screen_brightness_mode",
        "$TABLE_LINEAGE_SYSTEM/status_bar_brightness_control",
        "$TABLE_LINEAGE_SYSTEM/three_fingers_swipe",
    )

    // Platform state bundle keys (same meaning as AxPlatformClient's)
    const val STATE_ACTIVE = "active"
    const val STATE_TILE_STATE = "state"
    const val STATE_LABEL = "label"
    const val STATE_SECONDARY_LABEL = "secondary_label"
}
