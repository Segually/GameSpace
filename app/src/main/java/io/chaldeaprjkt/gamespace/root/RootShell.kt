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
package io.chaldeaprjkt.gamespace.root

import android.util.Log
import com.topjohnwu.superuser.Shell

/** Thin libsu wrapper used for operations that have no public API. */
object RootShell {
    private const val TAG = "GameSpace.Root"

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }

    /** Whether a root shell is (or can be) obtained. May prompt Magisk on first call. */
    fun isAvailable(): Boolean = runCatching { Shell.getShell().isRoot }.getOrDefault(false)

    /** Non-prompting check: true only if root was already granted to a cached shell. */
    fun isGrantedCached(): Boolean = Shell.isAppGrantedRoot() == true

    fun run(vararg commands: String): Boolean {
        if (!isAvailable()) return false
        val result = Shell.cmd(*commands).exec()
        if (!result.isSuccess) {
            Log.w(TAG, "Failed (${result.code}): ${commands.joinToString("; ")} -> ${result.err}")
        }
        return result.isSuccess
    }

    fun runAsync(vararg commands: String) {
        if (isGrantedCached()) Shell.cmd(*commands).submit()
    }

    /** Single-quote [value] for safe use as one shell argument. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
