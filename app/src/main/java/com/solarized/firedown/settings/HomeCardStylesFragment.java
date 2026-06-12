package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.preference.PreferenceManager;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.HomeCardStyle;

/**
 * Settings sub-screen behind 'Home cards' on general settings. Single
 * radio list with the three packaged styles; each row carries its own
 * miniature Downloads + Safe Folder preview rendered through the same
 * {@link HomeCardStyle#applyToCard} path the home page uses, so what
 * the user sees in the picker matches what they'll see on Home.
 *
 * <p>Selection is persisted to {@link Preferences#SETTINGS_HOME_CARD_STYLE};
 * {@code HomeFragment} picks it up on its next {@code ON_RESUME}.</p>
 */
public class HomeCardStylesFragment extends BasePreferenceFragment {

    private LinearLayout mContainer;
    @Nullable private View mSelectedRow;
    @NonNull private String mSelectedKey = Preferences.DEFAULT_HOME_CARD_STYLE;

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home_card_styles, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String stored = prefs.getString(
                Preferences.SETTINGS_HOME_CARD_STYLE,
                Preferences.DEFAULT_HOME_CARD_STYLE);
        // Normalise a removed style (blush/bloom) to the current default so
        // the radio matches what Home renders (HomeFragment falls back the
        // same way) — otherwise the picker would show nothing checked.
        mSelectedKey = HomeCardStyle.fromKey(stored, HomeCardStyle.TONAL).key;

        mContainer = view.findViewById(R.id.style_options_container);
        boolean night = HomeCardStyle.isNightMode(getResources());

        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (HomeCardStyle style : HomeCardStyle.ALL) {
            View row = inflater.inflate(R.layout.view_home_card_style_option, mContainer, false);
            bindRow(row, style, night);
            mContainer.addView(row);
        }
    }

    private void bindRow(@NonNull View row,
                         @NonNull HomeCardStyle style,
                         boolean night) {
        TextView name = row.findViewById(R.id.style_option_name);
        RadioButton radio = row.findViewById(R.id.style_option_radio);
        name.setText(style.nameRes);

        MaterialCardView downloadsCard = row.findViewById(R.id.style_option_downloads_card);
        HomeCardStyle.applyToCard(
                downloadsCard,
                row.findViewById(R.id.style_option_downloads_chip),
                (AppCompatImageView) row.findViewById(R.id.style_option_downloads_icon),
                row.findViewById(R.id.style_option_downloads_title),
                row.findViewById(R.id.style_option_downloads_subtitle),
                style.downloads(night));

        MaterialCardView vaultCard = row.findViewById(R.id.style_option_vault_card);
        HomeCardStyle.applyToCard(
                vaultCard,
                row.findViewById(R.id.style_option_vault_chip),
                (AppCompatImageView) row.findViewById(R.id.style_option_vault_icon),
                row.findViewById(R.id.style_option_vault_title),
                row.findViewById(R.id.style_option_vault_subtitle),
                style.vault(night));

        MaterialCardView trackersCard = row.findViewById(R.id.style_option_trackers_card);
        HomeCardStyle.applyToCard(
                trackersCard,
                row.findViewById(R.id.style_option_trackers_chip),
                (AppCompatImageView) row.findViewById(R.id.style_option_trackers_icon),
                row.findViewById(R.id.style_option_trackers_title),
                row.findViewById(R.id.style_option_trackers_subtitle),
                style.trackers(night));

        boolean selected = style.key.equals(mSelectedKey);
        radio.setChecked(selected);
        if (selected) mSelectedRow = row;

        row.setOnClickListener(v -> onRowClicked(row, radio, style));
    }

    private void onRowClicked(@NonNull View row,
                              @NonNull RadioButton radio,
                              @NonNull HomeCardStyle style) {
        if (style.key.equals(mSelectedKey)) return;

        if (mSelectedRow != null) {
            RadioButton previous = mSelectedRow.findViewById(R.id.style_option_radio);
            if (previous != null) previous.setChecked(false);
        }
        radio.setChecked(true);
        mSelectedRow = row;
        mSelectedKey = style.key;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit().putString(Preferences.SETTINGS_HOME_CARD_STYLE, style.key).apply();
    }
}
