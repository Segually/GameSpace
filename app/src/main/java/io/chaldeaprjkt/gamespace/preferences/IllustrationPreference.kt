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
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import io.chaldeaprjkt.gamespace.R

/** Lottie header illustration, standing in for SettingsLib's IllustrationPreference. */
class IllustrationPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private val rawRes: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.IllustrationPreference)
        rawRes = a.getResourceId(R.styleable.IllustrationPreference_lottie_rawRes, 0)
        a.recycle()
        layoutResource = R.layout.illustration_preference
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val view = holder.findViewById(R.id.lottie_view) as LottieAnimationView
        if (rawRes != 0) {
            view.setAnimation(rawRes)
            view.repeatCount = LottieDrawable.INFINITE
            view.playAnimation()
        }
    }
}
