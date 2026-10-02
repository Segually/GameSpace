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
package io.chaldeaprjkt.gamespace.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.bridge.Bridges
import io.chaldeaprjkt.gamespace.gamebar.DanmakuServiceListener
import io.chaldeaprjkt.gamespace.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Setup" category at the top of the main screen: shows whether root, both
 * Xposed hooks and the user-grantable permissions GameSpace relies on are in
 * place, and links to where each one is granted. The category hides itself
 * once everything is ready.
 */
class StatusPreferences(private val fragment: PreferenceFragmentCompat) {

    private val context get() = fragment.requireContext()
    private var category: PreferenceCategory? = null
    private var rootGranted = RootShell.isGrantedCached()

    private val runtimePermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { refresh() }

    private val bridgeListener = Bridges.Listener {
        fragment.activity?.runOnUiThread { refresh() }
    }

    fun attach(category: PreferenceCategory) {
        this.category = category
        Bridges.addListener(bridgeListener)
        Bridges.request(context)
        if (!rootGranted) requestRoot()
    }

    fun detach() {
        Bridges.removeListener(bridgeListener)
        category = null
    }

    fun refresh() {
        val category = category ?: return
        if (!fragment.isAdded) return
        category.removeAll()

        val items = listOf(
            item(
                R.string.status_root_title,
                rootGranted,
                R.string.status_root_summary_ok,
                R.string.status_root_summary_missing,
            ) { requestRoot() },
            item(
                R.string.status_system_hook_title,
                Bridges.isSystemConnected,
                R.string.status_hook_ok,
                R.string.status_hook_missing,
            ) { Bridges.request(context) },
            item(
                R.string.status_systemui_hook_title,
                Bridges.isPlatformConnected,
                R.string.status_hook_ok,
                R.string.status_hook_missing,
            ) { Bridges.request(context) },
            item(
                R.string.status_overlay_title,
                Settings.canDrawOverlays(context),
                R.string.status_permission_ok,
                R.string.status_permission_missing,
            ) {
                launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            item(
                R.string.status_listener_title,
                isListenerEnabled(),
                R.string.status_listener_summary,
                R.string.status_listener_summary,
            ) {
                launch(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, DanmakuServiceListener::class.java).flattenToString()
                    )
                )
            },
            item(
                R.string.status_dnd_title,
                context.getSystemService(NotificationManager::class.java)
                    .isNotificationPolicyAccessGranted,
                R.string.status_dnd_summary,
                R.string.status_dnd_summary,
            ) { launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            item(
                R.string.status_runtime_title,
                runtimePermissions.all {
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                },
                R.string.status_runtime_summary,
                R.string.status_runtime_summary,
            ) { permissionLauncher.launch(runtimePermissions) },
        )

        val missing = items.filterNot { it.second }
        missing.forEach { category.addPreference(it.first) }
        category.isVisible = missing.isNotEmpty()
    }

    private fun item(
        title: Int,
        ok: Boolean,
        okSummary: Int,
        missingSummary: Int,
        onClick: () -> Unit,
    ): Pair<Preference, Boolean> {
        val pref = Preference(context).apply {
            setTitle(title)
            setSummary(if (ok) okSummary else missingSummary)
            isPersistent = false
            setOnPreferenceClickListener {
                onClick()
                true
            }
        }
        return pref to ok
    }

    private fun isListenerEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(
                ComponentName(context, DanmakuServiceListener::class.java)
            )

    private fun requestRoot() {
        fragment.lifecycleScope.launch {
            rootGranted = withContext(Dispatchers.IO) { RootShell.isAvailable() }
            refresh()
        }
    }

    private fun launch(intent: Intent) {
        runCatching { fragment.startActivity(intent) }
    }
}
