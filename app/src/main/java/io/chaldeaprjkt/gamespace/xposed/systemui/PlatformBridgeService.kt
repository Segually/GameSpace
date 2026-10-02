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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteCallbackList
import de.robv.android.xposed.XposedHelpers
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.IPlatformBridge
import io.chaldeaprjkt.gamespace.bridge.IPlatformCallback
import io.chaldeaprjkt.gamespace.platform.PlatformClient
import io.chaldeaprjkt.gamespace.xposed.XLog
import java.lang.reflect.Proxy
import java.util.function.Consumer

/**
 * Replacement for AxionOS' AxPlatformService: exposes SystemUI's own
 * quick-settings tiles (created through QSFactoryImpl, so LineageOS tiles
 * work too) to the GameSpace gamebar.
 */
class PlatformBridgeService(
    private val context: Context,
    private val classLoader: ClassLoader,
    private val qsFactory: () -> Any?,
    private val onGestureLock: (Boolean) -> Unit,
) : IPlatformBridge.Stub() {

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = object : RemoteCallbackList<IPlatformCallback>() {
        override fun onCallbackDied(callback: IPlatformCallback) {
            // GameSpace died mid-session: never leave the back gesture locked.
            onGestureLock(false)
        }
    }

    /** feature id -> QSTile instance (SystemUI classloader) */
    private val tiles = LinkedHashMap<String, Any>()
    private val tileCallbacks = HashMap<String, Any>()
    private var tilesCreated = false
    private var listening = false

    @Volatile
    private var appUid = resolveAppUid()

    private fun resolveAppUid(): Int = runCatching {
        context.packageManager.getPackageUid(
            BridgeContract.PACKAGE, PackageManager.PackageInfoFlags.of(0)
        )
    }.getOrDefault(-1)

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid == appUid || uid == Process.myUid()) return
        appUid = resolveAppUid()
        if (uid != appUid) throw SecurityException("GameSpace platform bridge: uid $uid not allowed")
    }

    /** Run on SystemUI's main thread and wait for the result (QS tiles are main-thread objects). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val lock = Object()
        main.post {
            val r = runCatching(block)
            synchronized(lock) {
                result = r
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 2000
            while (result == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("SystemUI main thread timeout")
                lock.wait(left)
            }
        }
        return result!!.getOrThrow()
    }

    private fun <T> call(block: () -> T): T {
        checkCaller()
        val token = Binder.clearCallingIdentity()
        try {
            return onMain(block)
        } catch (e: RuntimeException) {
            throw e
        } catch (t: Throwable) {
            XLog.w("Platform bridge call failed", t)
            throw IllegalStateException(t.toString())
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun ensureTiles() {
        if (tilesCreated) return
        val factory = qsFactory() ?: return
        @Suppress("UNCHECKED_CAST")
        val available = (XposedHelpers.getObjectField(factory, "mTileMap") as? Map<String, *>)
            ?.keys ?: emptySet()
        val callbackClass = XposedHelpers.findClass(
            "com.android.systemui.plugins.qs.QSTile\$Callback", classLoader
        )
        for ((feature, spec) in PlatformClient.FEATURE_TO_TILE_SPEC) {
            if (spec !in available) continue
            val tile = runCatching { XposedHelpers.callMethod(factory, "createTile", spec) }
                .onFailure { XLog.w("createTile($spec)", it) }
                .getOrNull() ?: continue
            if (!(XposedHelpers.callMethod(tile, "isAvailable") as Boolean)) {
                XposedHelpers.callMethod(tile, "destroy")
                continue
            }
            val cb = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                when (method.name) {
                    "onStateChanged" -> {
                        XLog.guard("tile state $feature") {
                            args?.getOrNull(0)?.let { broadcast(feature, toBundle(it)) }
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "GameSpaceTileCallback($feature)"
                    else -> null
                }
            }
            XposedHelpers.callMethod(tile, "addCallback", cb)
            tiles[feature] = tile
            tileCallbacks[feature] = cb
        }
        tilesCreated = true
        XLog.i("Platform bridge tiles: ${tiles.keys}")
    }

    private fun toBundle(state: Any): Bundle = Bundle().apply {
        val tileState = XposedHelpers.getIntField(state, "state")
        putInt(BridgeContract.STATE_TILE_STATE, tileState)
        putBoolean(BridgeContract.STATE_ACTIVE, tileState == PlatformClient.TILE_STATE_ACTIVE)
        (XposedHelpers.getObjectField(state, "label") as? CharSequence)
            ?.let { putString(BridgeContract.STATE_LABEL, it.toString()) }
        (XposedHelpers.getObjectField(state, "secondaryLabel") as? CharSequence)
            ?.let { putString(BridgeContract.STATE_SECONDARY_LABEL, it.toString()) }
    }

    private fun broadcast(feature: String, state: Bundle) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onStateChanged(feature, state) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    override fun getVersion() = BridgeContract.VERSION

    override fun getSupportedFeatures(): Array<String> = call {
        ensureTiles()
        (tiles.keys + PlatformClient.FEATURE_SCREENSHOT).toTypedArray()
    }

    override fun getState(feature: String): Bundle? = call {
        ensureTiles()
        tiles[feature]?.let { toBundle(XposedHelpers.callMethod(it, "getState")) }
    }

    override fun toggle(feature: String) = call {
        if (feature == PlatformClient.FEATURE_SCREENSHOT) {
            screenshot()
            return@call
        }
        ensureTiles()
        tiles[feature]?.let { XposedHelpers.callMethod(it, "click", *arrayOf<Any?>(null)) }
        Unit
    }

    override fun setListening(listening: Boolean) = call {
        ensureTiles()
        if (this.listening == listening) return@call
        this.listening = listening
        tiles.values.forEach { tile ->
            XposedHelpers.callMethod(tile, "setListening", this, listening)
            if (listening) XposedHelpers.callMethod(tile, "refreshState")
        }
    }

    override fun registerCallback(callback: IPlatformCallback) {
        checkCaller()
        callbacks.register(callback)
    }

    override fun unregisterCallback(callback: IPlatformCallback) {
        checkCaller()
        callbacks.unregister(callback)
        if (callbacks.registeredCallbackCount == 0) {
            // GameSpace went away: stop keeping the tiles' controllers busy.
            main.post { runCatching { setListeningLocal(false) } }
        }
    }

    private fun setListeningLocal(listening: Boolean) {
        if (this.listening == listening) return
        this.listening = listening
        tiles.values.forEach { XposedHelpers.callMethod(it, "setListening", this, listening) }
    }

    override fun takeScreenshot() = call { screenshot() }

    override fun setGestureLock(locked: Boolean) {
        checkCaller()
        onGestureLock(locked)
    }

    private fun screenshot() {
        val helperClass = XposedHelpers.findClass("com.android.internal.util.ScreenshotHelper", null)
        val helper = XposedHelpers.newInstance(helperClass, context)
        val source = runCatching {
            XposedHelpers.getStaticIntField(
                XposedHelpers.findClass("android.view.WindowManager\$ScreenshotSource", null),
                "SCREENSHOT_GLOBAL_ACTIONS"
            )
        }.getOrDefault(0)
        XposedHelpers.callMethod(
            helper, "takeScreenshot",
            arrayOf(Int::class.javaPrimitiveType, Handler::class.java, Consumer::class.java),
            source, main, Consumer<Any?> { }
        )
    }
}
