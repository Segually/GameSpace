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
package io.chaldeaprjkt.gamespace.xposed.system

import android.app.ActivityOptions
import android.app.GameManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.RemoteCallbackList
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.window.TaskFpsCallback
import de.robv.android.xposed.XposedHelpers
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.IFpsListener
import io.chaldeaprjkt.gamespace.bridge.ISystemBridge
import io.chaldeaprjkt.gamespace.xposed.XLog
import java.util.concurrent.Executor

/**
 * Privileged operations GameSpace used to perform as a platform-signed system
 * app, executed inside system_server on its behalf. Only the GameSpace uid may
 * call in; every method runs with system_server's own identity afterwards.
 */
class SystemBridgeService(
    private val context: Context,
    private val focusedRootTaskId: () -> Int?,
    private val onGestureLock: (Boolean) -> Unit,
) : ISystemBridge.Stub() {

    @Volatile
    private var appUid = resolveAppUid()

    private val fpsListeners = RemoteCallbackList<IFpsListener>()
    private var fpsRegistered = false
    private val fpsCallback = object : TaskFpsCallback() {
        override fun onFpsReported(fps: Float) {
            val n = fpsListeners.beginBroadcast()
            try {
                for (i in 0 until n) {
                    runCatching { fpsListeners.getBroadcastItem(i).onFpsReported(fps) }
                }
            } finally {
                fpsListeners.finishBroadcast()
            }
        }
    }

    private val windowManager by lazy { context.getSystemService(WindowManager::class.java) }

    private fun resolveAppUid(): Int = runCatching {
        context.packageManager.getPackageUid(
            BridgeContract.PACKAGE, PackageManager.PackageInfoFlags.of(0)
        )
    }.getOrDefault(-1)

    private inline fun <T> privileged(block: () -> T): T {
        val uid = Binder.getCallingUid()
        if (uid != appUid && uid != Process.myUid()) {
            // The package may have been reinstalled with a new uid.
            appUid = resolveAppUid()
            if (uid != appUid) throw SecurityException("GameSpace bridge: uid $uid not allowed")
        }
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            XLog.w("Bridge call failed", t)
            throw IllegalStateException(t.toString())
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getVersion() = BridgeContract.VERSION

    override fun putSetting(table: String, key: String, value: String?): Boolean = privileged {
        if ("$table/$key" !in BridgeContract.WRITABLE_SETTINGS) {
            throw SecurityException("GameSpace bridge: $table/$key is not writable")
        }
        val resolver = context.contentResolver
        when (table) {
            BridgeContract.TABLE_SYSTEM -> Settings.System.putString(resolver, key, value)
            BridgeContract.TABLE_SECURE -> Settings.Secure.putString(resolver, key, value)
            BridgeContract.TABLE_GLOBAL -> Settings.Global.putString(resolver, key, value)
            BridgeContract.TABLE_LINEAGE_SYSTEM -> try {
                resolver.call(
                    LINEAGE_SETTINGS_URI, "PUT_system", key,
                    Bundle().apply { putString("value", value) }
                )
                true
            } catch (e: IllegalArgumentException) {
                XLog.w("Lineage setting $key not supported: ${e.message}")
                false
            }
            else -> false
        }
    }

    override fun setGameMode(packageName: String, mode: Int) = privileged {
        val gm = context.getSystemService(GameManager::class.java)
        XposedHelpers.callMethod(gm, "setGameMode", packageName, mode)
        Unit
    }

    /** For system_server's own use (system-wide mode): no caller check needed. */
    fun applyGameMode(packageName: String, mode: Int) {
        runCatching {
            val gm = context.getSystemService(GameManager::class.java)
            val available = XposedHelpers.callMethod(gm, "getAvailableGameModes", packageName) as IntArray
            if (mode in available) XposedHelpers.callMethod(gm, "setGameMode", packageName, mode)
        }.onFailure { XLog.w("applyGameMode($packageName)", it) }
    }

    override fun getAvailableGameModes(packageName: String): IntArray = privileged {
        val gm = context.getSystemService(GameManager::class.java)
        XposedHelpers.callMethod(gm, "getAvailableGameModes", packageName) as IntArray
    }

    override fun setGameIntervention(packageName: String, config: String?) = privileged {
        val deviceConfig = XposedHelpers.findClass("android.provider.DeviceConfig", null)
        if (config == null) {
            XposedHelpers.callStaticMethod(deviceConfig, "deleteProperty", GAME_OVERLAY_NAMESPACE, packageName)
        } else {
            XposedHelpers.callStaticMethod(
                deviceConfig, "setProperty", GAME_OVERLAY_NAMESPACE, packageName, config, false
            )
        }
        Unit
    }

    override fun registerFpsListener(listener: IFpsListener) = privileged {
        fpsListeners.register(listener)
        updateFpsRegistration(forceRebind = true)
    }

    override fun unregisterFpsListener(listener: IFpsListener) = privileged {
        fpsListeners.unregister(listener)
        updateFpsRegistration(forceRebind = false)
    }

    /** Re-target the FPS counter, e.g. when the focused game task changes. */
    fun onFocusedTaskChanged() {
        if (fpsListeners.registeredCallbackCount > 0) updateFpsRegistration(forceRebind = true)
    }

    @Synchronized
    private fun updateFpsRegistration(forceRebind: Boolean) {
        val wanted = fpsListeners.registeredCallbackCount > 0
        if (fpsRegistered && (!wanted || forceRebind)) {
            runCatching {
                XposedHelpers.callMethod(windowManager, "unregisterTaskFpsCallback", fpsCallback)
            }
            fpsRegistered = false
        }
        if (wanted && !fpsRegistered) {
            val taskId = focusedRootTaskId() ?: return
            runCatching {
                XposedHelpers.callMethod(
                    windowManager, "registerTaskFpsCallback",
                    taskId, Executor { it.run() }, fpsCallback
                )
                fpsRegistered = true
            }.onFailure { XLog.w("registerTaskFpsCallback($taskId)", it) }
        }
    }

    override fun getBrightnessInfo(): FloatArray? = privileged {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return@privileged null
        val info = XposedHelpers.callMethod(display, "getBrightnessInfo") ?: return@privileged null
        floatArrayOf(
            XposedHelpers.getFloatField(info, "brightness"),
            XposedHelpers.getFloatField(info, "brightnessMinimum"),
            XposedHelpers.getFloatField(info, "brightnessMaximum"),
        )
    }

    override fun setBrightness(linear: Float) = privileged {
        val dm = context.getSystemService(DisplayManager::class.java)
        XposedHelpers.callMethod(dm, "setBrightness", Display.DEFAULT_DISPLAY, linear)
        Unit
    }

    override fun setGestureLock(locked: Boolean) = privileged {
        onGestureLock(locked)
    }

    override fun trimAppCache(packageName: String) = privileged {
        val observer = XposedHelpers.findClass("android.content.pm.IPackageDataObserver", null)
        XposedHelpers.callMethod(
            context.packageManager, "deleteApplicationCacheFiles",
            arrayOf(String::class.java, observer), packageName, null
        )
        Unit
    }

    override fun launchFreeform(packageName: String): Boolean = privileged {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@privileged false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        XposedHelpers.callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
        val current = XposedHelpers.getStaticObjectField(android.os.UserHandle::class.java, "CURRENT")
        XposedHelpers.callMethod(
            context, "startActivityAsUser",
            arrayOf(Intent::class.java, Bundle::class.java, android.os.UserHandle::class.java),
            intent, options.toBundle(), current
        )
        true
    }

    private companion object {
        val LINEAGE_SETTINGS_URI: Uri = Uri.parse("content://lineagesettings")
        const val GAME_OVERLAY_NAMESPACE = "game_overlay"
        const val WINDOWING_MODE_FREEFORM = 5
    }
}
