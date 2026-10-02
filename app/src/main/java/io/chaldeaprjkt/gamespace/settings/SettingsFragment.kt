/*
 * Copyright (C) 2026 crDroid Android Project
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

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference

import androidx.preference.PreferenceFragmentCompat

import dagger.hilt.android.AndroidEntryPoint

import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.BypassCharging
import io.chaldeaprjkt.gamespace.data.GameOptimizationManager
import io.chaldeaprjkt.gamespace.data.GameStore
import io.chaldeaprjkt.gamespace.preferences.AppListPreferences
import io.chaldeaprjkt.gamespace.preferences.QuickStartAppPreference
import io.chaldeaprjkt.gamespace.preferences.QuickStartAppPreferenceDialogFragment
import io.chaldeaprjkt.gamespace.preferences.appselector.AppSelectorActivity

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint(PreferenceFragmentCompat::class)
class SettingsFragment : Hilt_SettingsFragment(),
    QuickStartAppPreferenceDialogFragment.QuickStartAppListener,
    Preference.OnPreferenceChangeListener {

    private var apps: AppListPreferences? = null
    private val status = StatusPreferences(this)

    @Inject
    lateinit var gameOptimization: GameOptimizationManager

    private val selectorResult =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            apps?.useSelectorResult(it)
        }

    private val perAppResult =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            apps?.usePerAppResult(it)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findPreference<PreferenceCategory>("status")?.let { status.attach(it) }

        // Only devices with a hardware gaming switch (Lenovo TB-9707F) get this section.
        findPreference<PreferenceCategory>("gaming_switch")?.isVisible = hasGamingSwitch()
        findPreference<ListPreference>(AppSettings.KEY_SWITCH_ACTION)?.apply {
            val store = GameStore(requireContext())
            value = store.switchAction
            setOnPreferenceChangeListener { _, newValue ->
                store.switchAction = newValue as String
                true
            }
        }

        findPreference<Preference>(AppSettings.KEY_BYPASS_CHARGE)?.let { pref ->
            viewLifecycleOwner.lifecycleScope.launch {
                pref.isVisible = withContext(Dispatchers.IO) { BypassCharging.isSupported() }
            }
        }

        apps = findPreference("gamespace_game_list")
        apps?.onRegisteredAppClick { pkg ->
            perAppResult.launch(
                Intent(context, PerAppSettingsActivity::class.java).apply {
                    putExtra(PerAppSettingsActivity.EXTRA_PACKAGE, pkg)
                }
            )
        }

        findPreference<Preference>(AppListPreferences.KEY_ADD_GAME)
            ?.setOnPreferenceClickListener {
                selectorResult.launch(Intent(context, AppSelectorActivity::class.java))
                true
            }

        // Game Optimization preferences
        findPreference<SwitchPreferenceCompat>("game_memory_management")?.apply {
            isChecked = gameOptimization.isMemoryManagementEnabled
            onPreferenceChangeListener = this@SettingsFragment
        }

        findPreference<SwitchPreferenceCompat>("game_cache_management")?.apply {
            isChecked = gameOptimization.isCacheManagementEnabled
            onPreferenceChangeListener = this@SettingsFragment
        }
    }

    private fun hasGamingSwitch() = InputDevice.getDeviceIds().any {
        InputDevice.getDevice(it)?.name == GAMING_SWITCH_DEVICE
    }

    override fun onResume() {
        super.onResume()
        apps?.updateAppList()
        status.refresh()
    }

    override fun onDestroyView() {
        status.detach()
        super.onDestroyView()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is QuickStartAppPreference) {
            val dialogFragment =
                QuickStartAppPreferenceDialogFragment.newInstance(preference.key)
            dialogFragment.setListener(this)
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, "QuickStartAppPreferenceDialogFragment")
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun getSavedQuickStartApps(): String {
        val prefs = preferenceManager.sharedPreferences ?: return ""
        return prefs.getString(io.chaldeaprjkt.gamespace.data.AppSettings.KEY_QUICK_START_APPS, "") ?: ""
    }

    override fun saveQuickStartApps(apps: String) { /* no-op */ }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            "game_memory_management" -> {
                gameOptimization.isMemoryManagementEnabled = newValue as Boolean
                return true
            }
            "game_cache_management" -> {
                gameOptimization.isCacheManagementEnabled = newValue as Boolean
                return true
            }
        }
        return false
    }

    private companion object {
        const val GAMING_SWITCH_DEVICE = "game_mode_switcher"
    }
}
