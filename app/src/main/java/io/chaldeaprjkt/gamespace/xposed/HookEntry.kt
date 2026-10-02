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
package io.chaldeaprjkt.gamespace.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.chaldeaprjkt.gamespace.xposed.system.SystemServerHooks
import io.chaldeaprjkt.gamespace.xposed.systemui.SystemUIHooks

/** Entry point listed in assets/xposed_init. */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            // system_server is reported as package "android", process "android"
            "android" -> if (lpparam.processName == "android") {
                XLog.guard("system_server") { SystemServerHooks.install(lpparam.classLoader) }
            }
            "com.android.systemui" -> if (lpparam.isFirstApplication) {
                XLog.guard("SystemUI") { SystemUIHooks.install(lpparam.classLoader) }
            }
        }
    }
}
