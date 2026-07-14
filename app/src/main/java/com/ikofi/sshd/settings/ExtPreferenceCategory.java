package com.ikofi.sshd.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;

/**
 * In your "res/som_prefs.xml" file(s), replace {@code <PreferenceCategory ...}
 * with {@code <com.ikofi.sshd.settings.ExtPreferenceCategory ...}
 * and the {@code android:summary} attribute will be able to display multiple lines
 * of text.
 */
@SuppressWarnings("unused")
public class ExtPreferenceCategory
        extends PreferenceCategory {

    public ExtPreferenceCategory(@NonNull final Context context,
                                 @Nullable final AttributeSet attrs,
                                 final int defStyleAttr,
                                 final int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ExtPreferenceCategory(@NonNull final Context context,
                                 @Nullable final AttributeSet attrs,
                                 final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ExtPreferenceCategory(@NonNull final Context context,
                                 @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public ExtPreferenceCategory(@NonNull final Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(@NonNull final PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        final TextView summaryView = (TextView) holder.findViewById(android.R.id.summary);
        if (summaryView != null) {
            summaryView.setSingleLine(false);
            //summaryView.setMaxLines(10);
        }
    }
}
