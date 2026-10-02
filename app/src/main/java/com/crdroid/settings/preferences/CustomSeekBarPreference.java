/*
 * Copyright (C) 2016-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.crdroid.settings.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

import io.chaldeaprjkt.gamespace.R;

/**
 * Seek bar preference that renders its value with optional units/sign and a
 * custom label for the default value. Built on androidx SeekBarPreference
 * (the SettingsLib SliderPreference it used to extend is platform-only).
 */
public class CustomSeekBarPreference extends SeekBarPreference {

    private static final String ANDROIDNS = "http://schemas.android.com/apk/res/android";

    private boolean mShowSign;
    @Nullable
    private String mUnits = "";
    @Nullable
    private String mDefaultValueText;
    private boolean mDefaultValueExists;
    private int mDefaultValue;

    public CustomSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        readAttrs(context, attrs);
        setShowSeekBarValue(false);
    }

    public CustomSeekBarPreference(Context context) {
        this(context, null);
    }

    private void readAttrs(Context c, @Nullable AttributeSet attrs) {
        if (attrs == null) return;
        final TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.CustomSeekBarPreference);
        try {
            mShowSign = a.getBoolean(R.styleable.CustomSeekBarPreference_showSign, false);
            final String units = a.getString(R.styleable.CustomSeekBarPreference_units);
            if (units != null) mUnits = units;
            setUpdatesContinuously(a.getBoolean(
                    R.styleable.CustomSeekBarPreference_continuousUpdates, false));
            mDefaultValueText = a.getString(R.styleable.CustomSeekBarPreference_defaultValueText);
            final int interval = a.getInt(R.styleable.CustomSeekBarPreference_interval, 0);
            if (interval > 0) setSeekBarIncrement(interval);
        } finally {
            a.recycle();
        }
        final String defaultValue = attrs.getAttributeValue(ANDROIDNS, "defaultValue");
        if (defaultValue != null) {
            try {
                mDefaultValue = Integer.parseInt(defaultValue);
                mDefaultValueExists = true;
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private String formatValue(int v) {
        if (mDefaultValueExists && mDefaultValueText != null && v == mDefaultValue) {
            return mDefaultValueText;
        }
        String s = String.valueOf(v);
        if (mShowSign && v > 0) s = "+" + s;
        if (mUnits != null && !mUnits.isEmpty()) s = s + " " + mUnits;
        return s;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        final SeekBar seekBar = (SeekBar) holder.findViewById(R.id.seekbar);
        if (summary == null) return;
        summary.setVisibility(android.view.View.VISIBLE);
        summary.setText(formatValue(getValue()));
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    summary.setText(formatValue(progress + getMin()));
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    final int value = sb.getProgress() + getMin();
                    if (value != getValue() && callChangeListener(value)) {
                        setValue(value);
                    } else {
                        sb.setProgress(getValue() - getMin());
                    }
                }
            });
        }
    }
}
