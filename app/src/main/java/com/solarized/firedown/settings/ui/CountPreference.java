package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * A read-only settings row with a COUNT in its trailing widget slot — the
 * "Today by request type" and "Most blocked domains today" rows of the
 * blocked-stats screen (title = the type, or the host through HostLabel;
 * widget = the locale-formatted number, always shown, 1 included). The
 * per-page sheet's rows (item_blocked_tracker_host) copy this anatomy. Not a
 * control: never selectable, never persisted. Lives in the grouped cards like
 * any other row, so a list of them reads as one block.
 */
public class CountPreference extends Preference {

    private long mCount;

    public CountPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CountPreference(@NonNull Context context) {
        super(context);
        init();
    }

    private void init() {
        setWidgetLayoutResource(R.layout.preference_widget_count);
        setSelectable(false);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    public void setCount(long count) {
        if (mCount == count) {
            return;
        }
        mCount = count;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView count = (TextView) holder.findViewById(R.id.preference_count);
        if (count != null) {
            // Allocated per bind, not cached: the row follows a locale change
            // without the screen being recreated. A handful of rows per push.
            count.setText(NumberFormat.getInstance(Locale.getDefault()).format(mCount));
        }
    }
}
