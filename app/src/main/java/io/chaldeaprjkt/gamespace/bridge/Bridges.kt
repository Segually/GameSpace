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

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.chaldeaprjkt.gamespace.root.RootShell
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide holder for the binders the Xposed hooks hand to the app.
 * Everything here degrades to a no-op (or a root fallback) when a hook is
 * not active.
 */
object Bridges {
    private const val TAG = "GameSpace.Bridges"

    fun interface Listener {
        fun onBridgesChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var system: ISystemBridge? = null
        private set

    @Volatile
    var platform: IPlatformBridge? = null
        private set

    val isSystemConnected get() = system?.asBinder()?.isBinderAlive == true
    val isPlatformConnected get() = platform?.asBinder()?.isBinderAlive == true

    fun attachSystem(binder: IBinder?) {
        val bridge = binder?.let { ISystemBridge.Stub.asInterface(it) } ?: return
        if (system?.asBinder() == binder) return
        runCatching { binder.linkToDeath({ detachSystem(binder) }, 0) }
        system = bridge
        Log.i(TAG, "System bridge attached")
        notifyListeners()
    }

    fun attachPlatform(binder: IBinder?) {
        val bridge = binder?.let { IPlatformBridge.Stub.asInterface(it) } ?: return
        if (platform?.asBinder() == binder) return
        runCatching { binder.linkToDeath({ detachPlatform(binder) }, 0) }
        platform = bridge
        Log.i(TAG, "Platform bridge attached")
        notifyListeners()
    }

    private fun detachSystem(binder: IBinder) {
        if (system?.asBinder() == binder) system = null
        notifyListeners()
    }

    private fun detachPlatform(binder: IBinder) {
        if (platform?.asBinder() == binder) platform = null
        notifyListeners()
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)
    private fun notifyListeners() = listeners.forEach { it.onBridgesChanged() }

    /** Ask the hooks to push their binders into [io.chaldeaprjkt.gamespace.provider.GameSpaceProvider]. */
    fun request(context: Context) {
        if (isSystemConnected && isPlatformConnected) return
        for (target in listOf("android", "com.android.systemui")) {
            context.sendBroadcast(
                Intent(BridgeContract.ACTION_REQUEST_BRIDGE).setPackage(target)
            )
        }
    }

    inline fun <T> withSystem(default: T, block: (ISystemBridge) -> T): T {
        val bridge = system ?: return default
        return runCatching { block(bridge) }
            .onFailure { Log.w("GameSpace.Bridges", "System bridge call failed", it) }
            .getOrDefault(default)
    }

    inline fun <T> withPlatform(default: T, block: (IPlatformBridge) -> T): T {
        val bridge = platform ?: return default
        return runCatching { block(bridge) }
            .onFailure { Log.w("GameSpace.Bridges", "Platform bridge call failed", it) }
            .getOrDefault(default)
    }

    /**
     * Write a system setting through the system_server hook, falling back to a
     * root shell. Reads never need this: the app can read these tables itself.
     */
    fun putSetting(table: String, key: String, value: String?): Boolean {
        if (isSystemConnected) return withSystem(false) { it.putSetting(table, key, value) }
        val cmd = when (table) {
            BridgeContract.TABLE_LINEAGE_SYSTEM ->
                if (value == null) {
                    "content delete --uri content://lineagesettings/system --where ${RootShell.quote("name='$key'")}"
                } else {
                    "content call --uri content://lineagesettings --method PUT_system " +
                        "--arg ${RootShell.quote(key)} --extra value:s:${RootShell.quote(value)}"
                }
            else ->
                if (value == null) "settings delete $table ${RootShell.quote(key)}"
                else "settings put $table ${RootShell.quote(key)} ${RootShell.quote(value)}"
        }
        return RootShell.run(cmd)
    }
}
