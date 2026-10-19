package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;

/**
 * A {@link CountPreference} whose title is a blocked HOST — the "Most blocked
 * domains today" rows of Settings → Trackers and ads blocked. The title is
 * 14sp (body-medium) rather than the settings title's 16sp, the size of the
 * per-page "Blocked on this page" sheet's rows (item_blocked_tracker_host):
 * a host is a fact to scan, not a menu item, and a column of 16sp hosts read
 * louder than anything else on the screen.
 *
 * <p>A subclass, not a flag on CountPreference: the preference adapter keys
 * its view types by class, so the host rows never share a recycled title
 * view with the 16sp request-type rows and nothing has to be restored.</p>
 */
public class HostCountPreference extends CountPreference {

    private static final float HOST_TITLE_SP = 14f;

    public HostCountPreference(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, HOST_TITLE_SP);
        }
    }
}
