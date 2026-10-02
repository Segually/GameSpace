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
package io.chaldeaprjkt.gamespace.xposed.systemui

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.xposed.XLog

/**
 * SystemUI side of GameSpace, replacing AxionOS' AxPlatformService and the
 * back-gesture half of the gaming gesture lock.
 *
 * Hook points verified against LineageOS 23.0 (Android 16) SystemUI:
 *  - Instrumentation#callApplicationOnCreate(Application)                 init
 *    (the Application class name varies: SystemUIApplicationImpl on 23.2)
 *  - qs.tileimpl.QSFactoryImpl(<init>)                                   tile factory
 *  - navigationbar.gestural.EdgeBackGestureHandler#isWithinInsets(int, int)  back lock
 */
object SystemUIHooks {

    private const val SYSUI = "com.android.systemui"

    @Volatile
    private var qsFactory: Any? = null

    @Volatile
    private var gestureLocked = false

    private var initialized = false

    fun install(cl: ClassLoader) {
        // Each hook is independent: a missing class only disables its own feature.
        XLog.guard("Application init hook") {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java, "callApplicationOnCreate", Application::class.java,
                XLog.after { param ->
                    val app = param.args[0] as Application
                    if (app.packageName == SYSUI && !initialized) {
                        initialized = true
                        init(app, cl)
                    }
                }
            )
        }

        XLog.guard("QSFactoryImpl hook") {
            XposedBridge.hookAllConstructors(
                XposedHelpers.findClass("$SYSUI.qs.tileimpl.QSFactoryImpl", cl),
                XLog.after { param -> qsFactory = param.thisObject }
            )
            XLog.i("Hooked QSFactoryImpl")
        }

        XLog.hook(
            cl, "$SYSUI.navigationbar.gestural.EdgeBackGestureHandler", "isWithinInsets",
            Int::class.java, Int::class.java,
            XLog.before { param -> if (gestureLocked) param.result = false }
        )
    }

    private fun init(app: Application, cl: ClassLoader) {
        val bridge = PlatformBridgeService(
            context = app,
            classLoader = cl,
            qsFactory = { qsFactory },
            onGestureLock = { gestureLocked = it },
        )
        val thread = HandlerThread("GameSpace-Hook").apply { start() }
        val handler = Handler(thread.looper)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = XLog.guard("bridge request") {
                // The binder only ever goes to GameSpace's provider, and each call is uid-checked.
                runCatching {
                    app.contentResolver.call(
                        BridgeContract.AUTHORITY_URI, BridgeContract.METHOD_ATTACH_SYSTEMUI, null,
                        Bundle().apply { putBinder(BridgeContract.KEY_BINDER, bridge) }
                    )
                }.onFailure { XLog.w("attach_systemui failed: ${it.message}") }
            }
        }
        app.registerReceiver(
            receiver, IntentFilter(BridgeContract.ACTION_REQUEST_BRIDGE),
            null, handler, Context.RECEIVER_EXPORTED
        )
        XLog.i("SystemUI hooks ready")
    }
}
