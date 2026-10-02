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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.KeyEvent
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.xposed.XLog

/**
 * system_server side of GameSpace, replacing the ROM patches
 * (GameSpaceService, GamePackageHandler and the DisplayPolicy gesture lock).
 *
 * Hook points verified against LineageOS 23.0 (Android 16) frameworks/base:
 *  - ActivityManagerService#systemReady(...)                              init
 *  - ActivityTaskManagerService#setLastResumedActivityUncheckLocked(ActivityRecord, String)
 *  - ActivityTaskSupervisor#removeTask(Task, boolean, boolean, String, int, int, String)
 *  - KeyguardController#setKeyguardShown(int, boolean, boolean)
 *  - DisplayPolicy#requestTransientBars(WindowState, boolean)              gesture lock
 *  - PhoneWindowManager#interceptKeyBeforeQueueing(KeyEvent, int)          gaming switch
 */
object SystemServerHooks {

    private const val WM = "com.android.server.wm"
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val SCAN_SWITCH_ON = 249
    private const val SCAN_SWITCH_OFF = 250

    private var dispatcher: SessionDispatcher? = null
    private var activityTaskManager: Any? = null

    @Volatile
    private var sessionActive = false

    @Volatile
    private var gestureLocked = false

    fun install(cl: ClassLoader) {
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass("com.android.server.am.ActivityManagerService", cl),
            "systemReady",
            XLog.after { param ->
                if (dispatcher == null) {
                    init(XposedHelpers.getObjectField(param.thisObject, "mContext") as Context)
                }
            }
        )

        XLog.hook(
            cl, "$WM.ActivityTaskManagerService", "setLastResumedActivityUncheckLocked",
            "$WM.ActivityRecord", String::class.java,
            XLog.after { param ->
                activityTaskManager = param.thisObject
                val d = dispatcher ?: return@after
                val record = param.args[0] ?: return@after
                val pkg = XposedHelpers.getObjectField(record, "packageName") as? String
                    ?: return@after
                val task = XposedHelpers.callMethod(record, "getTask")
                val freeform = task != null &&
                    XposedHelpers.callMethod(task, "getWindowingMode") as Int == WINDOWING_MODE_FREEFORM
                // e.g. a sign-in screen from Play Services/microG opened inside the game's task
                val taskOwner = task?.let { taskRootPackage(it) }
                val home = task != null &&
                    XposedHelpers.callMethod(task, "isActivityTypeHomeOrRecents") as Boolean
                d.post { onAppFocused(pkg, taskOwner, freeform, home) }
            }
        )

        XLog.hook(
            cl, "$WM.ActivityTaskSupervisor", "removeTask",
            "$WM.Task", Boolean::class.java, Boolean::class.java,
            String::class.java, Int::class.java, Int::class.java,
            String::class.java,
            XLog.before { param ->
                val d = dispatcher ?: return@before
                val pkg = taskPackage(param.args[0] ?: return@before) ?: return@before
                d.post { onTaskRemoved(pkg) }
            }
        )

        XLog.hook(
            cl, "$WM.KeyguardController", "setKeyguardShown",
            Int::class.java, Boolean::class.java,
            Boolean::class.java,
            XLog.after { param ->
                if (param.args[0] as Int != 0) return@after
                val showing = param.args[1] as Boolean
                dispatcher?.post { onKeyguardChanged(showing) }
            }
        )

        // Lenovo TB-9707F gaming switch: the kernel's "game_mode_switcher" input device sends
        // scan code 249 (on) / 250 (off). GSIs have no key layout for it, so it arrives as
        // KEYCODE_UNKNOWN and would otherwise be dropped.
        XLog.hook(
            cl, "com.android.server.policy.PhoneWindowManager", "interceptKeyBeforeQueueing",
            KeyEvent::class.java, Int::class.java,
            XLog.before { param ->
                val event = param.args[0] as KeyEvent
                if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) return@before
                val on = when (event.scanCode) {
                    SCAN_SWITCH_ON -> true
                    SCAN_SWITCH_OFF -> false
                    else -> return@before
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dispatcher?.post { onGameSwitch(on) }
                }
                param.result = 0 // consumed: don't pass to apps
            }
        )

        XLog.hook(
            cl, "$WM.DisplayPolicy", "requestTransientBars",
            "$WM.WindowState", Boolean::class.java,
            XLog.before { param ->
                // Swiping in the system bars of an immersive game is ignored while locked.
                if (gestureLocked && sessionActive) param.result = null
            }
        )
    }

    /** Package that started the task (its root activity), regardless of what's on top. */
    private fun taskRootPackage(task: Any): String? {
        val real = XposedHelpers.getObjectField(task, "realActivity") as? ComponentName
        if (real != null) return real.packageName
        return (XposedHelpers.callMethod(task, "getBaseIntent") as? Intent)?.component?.packageName
    }

    private fun taskPackage(task: Any): String? {
        val top = XposedHelpers.callMethod(task, "getTopMostActivity")
        (top?.let { XposedHelpers.getObjectField(it, "packageName") } as? String)?.let { return it }
        val base = XposedHelpers.callMethod(task, "getBaseIntent") as? Intent
        return base?.component?.packageName
    }

    private fun isTopApp(packageName: String): Boolean {
        val atm = activityTaskManager ?: return false
        val topApp = XposedHelpers.getObjectField(atm, "mTopApp") ?: return false
        return XposedHelpers.callMethod(topApp, "containsPackage", packageName) as Boolean
    }

    private fun focusedRootTaskId(): Int? {
        val atm = activityTaskManager ?: return null
        val info = XposedHelpers.callMethod(atm, "getFocusedRootTaskInfo") ?: return null
        return XposedHelpers.getIntField(info, "taskId")
    }

    private fun init(context: Context) {
        val thread = HandlerThread("GameSpace-Hook", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        val handler = Handler(thread.looper)

        val games = GameListCache(context, handler)
        val bridge = SystemBridgeService(
            context,
            focusedRootTaskId = { runCatching { focusedRootTaskId() }.getOrNull() },
            onGestureLock = { gestureLocked = it },
        )
        val d = SessionDispatcher(
            context, handler, games, bridge,
            isTopApp = { runCatching { isTopApp(it) }.getOrDefault(false) },
            onSessionChanged = { active ->
                sessionActive = active
                if (!active) gestureLocked = false
            },
        )
        games.onChanged = { d.onGameListChanged() }
        handler.post { games.start() }

        registerBridgeRequests(context, handler, games, bridge)
        registerPackageReceiver(context, handler, games)

        dispatcher = d
        XLog.i("system_server hooks ready")
    }

    /** The app asks for our binder whenever its process (re)starts. */
    private fun registerBridgeRequests(
        context: Context, handler: Handler, games: GameListCache, bridge: SystemBridgeService,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = XLog.guard("bridge request") {
                // Harmless if spoofed: the binder only ever goes to GameSpace's own provider,
                // and every bridge call is checked against GameSpace's uid.
                games.call(
                    BridgeContract.METHOD_ATTACH_SYSTEM, null,
                    Bundle().apply { putBinder(BridgeContract.KEY_BINDER, bridge) }
                )
            }
        }
        context.registerReceiver(
            receiver, IntentFilter(BridgeContract.ACTION_REQUEST_BRIDGE),
            null, handler, Context.RECEIVER_EXPORTED
        )
    }

    /** Port of GamePackageHandler: auto-add newly installed games, drop uninstalled ones. */
    private fun registerPackageReceiver(context: Context, handler: Handler, games: GameListCache) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = XLog.guard("package change") {
                val pkg = intent.data?.schemeSpecificPart ?: return@guard
                if (pkg == BridgeContract.PACKAGE) return@guard
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return@guard
                        if (games.isGame(pkg) || games.isDenied(pkg) || !games.autoDetect) return@guard
                        if (!isGameCategory(context, pkg)) return@guard
                        games.call(BridgeContract.METHOD_ADD_GAME, pkg)
                    }
                    Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                        if (games.isGame(pkg)) games.call(BridgeContract.METHOD_REMOVE_GAME, pkg)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun isGameCategory(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(
            pkg, PackageManager.ApplicationInfoFlags.of(0)
        ).category == ApplicationInfo.CATEGORY_GAME
    }.getOrDefault(false)
}
