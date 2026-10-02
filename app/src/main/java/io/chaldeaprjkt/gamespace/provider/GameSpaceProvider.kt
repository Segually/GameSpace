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
package io.chaldeaprjkt.gamespace.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.bridge.BridgeContract
import io.chaldeaprjkt.gamespace.bridge.Bridges
import io.chaldeaprjkt.gamespace.data.GameStore

/**
 * Rendezvous point between the app and the Xposed hooks.
 *
 * - system_server reads the game list and reports auto-detected games.
 * - Both hooks hand over their bridge binders.
 *
 * The provider is exported without a permission (SystemUI holds none of ours),
 * so every method checks the calling uid itself.
 */
class GameSpaceProvider : ContentProvider() {

    private val store by lazy { GameStore(requireNotNull(context)) }

    private val systemUiUid by lazy {
        runCatching {
            requireNotNull(context).packageManager.getPackageUid(
                SYSTEMUI_PACKAGE, PackageManager.PackageInfoFlags.of(0)
            )
        }.getOrDefault(-1)
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        val isSelf = uid == Process.myUid()
        val isSystem = uid == Process.SYSTEM_UID
        val isSystemUi = uid == systemUiUid

        return when (method) {
            BridgeContract.METHOD_ATTACH_SYSTEM -> {
                require(isSystem) { "attach_system from uid $uid" }
                Bridges.attachSystem(extras?.getBinder(BridgeContract.KEY_BINDER))
                null
            }
            BridgeContract.METHOD_ATTACH_SYSTEMUI -> {
                require(isSystemUi) { "attach_systemui from uid $uid" }
                Bridges.attachPlatform(extras?.getBinder(BridgeContract.KEY_BINDER))
                null
            }
            BridgeContract.METHOD_GET_STATE -> {
                require(isSystem || isSelf) { "get_state from uid $uid" }
                withCleanIdentity {
                    Bundle().apply {
                        putString(BridgeContract.KEY_GAME_LIST, store.rawGameList)
                        putStringArray(
                            BridgeContract.KEY_DENIED_LIST, store.deniedList.toTypedArray()
                        )
                        putBoolean(BridgeContract.KEY_AUTO_DETECT, store.autoDetect)
                    }
                }
            }
            BridgeContract.METHOD_ADD_GAME, BridgeContract.METHOD_REMOVE_GAME -> {
                require(isSystem || isSelf) { "$method from uid $uid" }
                val pkg = arg?.takeIf { PACKAGE_NAME.matches(it) } ?: return null
                val changed = withCleanIdentity {
                    if (method == BridgeContract.METHOD_ADD_GAME) store.addGame(pkg)
                    else store.removeGame(pkg)
                }
                if (changed && method == BridgeContract.METHOD_ADD_GAME) notifyGameAdded(pkg)
                Bundle().apply { putBoolean(BridgeContract.KEY_RESULT, changed) }
            }
            else -> {
                Log.w(TAG, "Unknown method $method from uid $uid")
                null
            }
        }
    }

    /** Auto-detect feedback, as the ROM's GamePackageHandler did with a toast. */
    private fun notifyGameAdded(packageName: String) {
        val ctx = context ?: return
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0)
                )
            )
        }.getOrDefault(packageName)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, ctx.getString(R.string.game_auto_added, label), Toast.LENGTH_LONG).show()
        }
    }

    private inline fun <T> withCleanIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ) = 0

    private companion object {
        const val TAG = "GameSpace.Provider"
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        val PACKAGE_NAME = Regex("[a-zA-Z0-9_.]+")
    }
}
