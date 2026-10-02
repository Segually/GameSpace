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

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Logging + defensive hooking. A hook that fails to install only disables its
 * own feature; it must never take system_server or SystemUI down with it.
 */
object XLog {
    const val TAG = "GameSpace-Xposed"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        XposedBridge.log("$TAG: $msg${t?.let { " ($it)" } ?: ""}")
    }

    inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            w("Failed: $what", t)
        }
    }

    /** Hook a method by class/method name; returns false (and logs) when it doesn't exist. */
    fun hook(
        classLoader: ClassLoader,
        className: String,
        method: String,
        vararg paramsAndCallback: Any,
    ): Boolean = try {
        XposedHelpers.findAndHookMethod(className, classLoader, method, *paramsAndCallback)
        i("Hooked $className#$method")
        true
    } catch (t: Throwable) {
        w("Cannot hook $className#$method", t)
        false
    }

    /** `afterHookedMethod` helper whose body can't crash the hooked process. */
    fun after(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                block(param)
            } catch (t: Throwable) {
                w("after(${param.method.name})", t)
            }
        }
    }

    /** `beforeHookedMethod` helper whose body can't crash the hooked process. */
    fun before(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                block(param)
            } catch (t: Throwable) {
                w("before(${param.method.name})", t)
            }
        }
    }
}
