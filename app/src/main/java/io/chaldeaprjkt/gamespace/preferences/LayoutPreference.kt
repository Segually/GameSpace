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
package io.chaldeaprjkt.gamespace.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.chaldeaprjkt.gamespace.R

/**
 * Minimal replacement for SettingsLib's LayoutPreference: inflates `android:layout`
 * eagerly so callers can look up child views before the preference is bound.
 */
class LayoutPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private val content: View

    init {
        val layoutRes = attrs?.getAttributeResourceValue(ANDROID_NS, "layout", 0) ?: 0
        content = LayoutInflater.from(context).inflate(layoutRes, null, false)
        layoutResource = R.layout.layout_preference_frame
        isSelectable = false
    }

    fun <T : View> findViewById(id: Int): T? = content.findViewById(id)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val frame = holder.itemView as FrameLayout
        if (content.parent !== frame) {
            (content.parent as? ViewGroup)?.removeView(content)
            frame.removeAllViews()
            frame.addView(content)
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
