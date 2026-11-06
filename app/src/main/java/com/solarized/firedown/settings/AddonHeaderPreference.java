package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.FlatPreference;

/**
 * The top of one add-on's page ({@code preference_addon_header.xml}): a
 * 48dp icon, the name, author and version, and the description. Flat on the
 * page, not a card: as a card row it put its text 37dp right of every row
 * under it. Fed by {@link AddonDetailFragment} through {@link #set}.
 */
public class AddonHeaderPreference extends Preference implements FlatPreference {

    private CharSequence mName = "";
    private CharSequence mFacts = "";
    private CharSequence mDescription = "";
    @Nullable
    private Drawable mAddonIcon;

    public AddonHeaderPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_addon_header);
        setSelectable(false);
        setPersistent(false);
    }

    /** A value that did not change is a no-op. */
    public void set(@NonNull CharSequence name, @NonNull CharSequence facts,
                    @NonNull CharSequence description) {
        if (TextUtils.equals(mName, name) && TextUtils.equals(mFacts, facts)
                && TextUtils.equals(mDescription, description)) {
            return;
        }
        mName = name;
        mFacts = facts;
        mDescription = description;
        notifyChanged();
    }

    public void setAddonIcon(@Nullable Drawable icon) {
        mAddonIcon = icon;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ImageView icon = (ImageView) holder.findViewById(R.id.addon_header_icon);
        TextView name = (TextView) holder.findViewById(R.id.addon_header_name);
        TextView facts = (TextView) holder.findViewById(R.id.addon_header_facts);
        TextView description = (TextView) holder.findViewById(R.id.addon_header_description);
        if (icon == null || name == null || facts == null || description == null) {
            return;
        }
        icon.setImageDrawable(mAddonIcon);
        name.setText(mName);
        facts.setText(mFacts);
        facts.setVisibility(mFacts.length() == 0 ? View.GONE : View.VISIBLE);
        description.setText(mDescription);
        description.setVisibility(mDescription.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
