package com.solarized.firedown.phone.dialogs;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.ViewModelProvider;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Home "more" bottom sheet — slimmer sibling of the Browser popup.
 *
 * <p>Replaces the RecyclerView-of-rows with a static layout of two
 * MaterialCard sections (library/app and conditional destructive).
 * The popup carries only items that don't already have a primary
 * surface on Home — History and Settings — plus Quit when the
 * {@link Preferences#SETTINGS_QUIT_PREF} preference is on. The
 * structure matches the Browser popup so the two sheets share a
 * vocabulary.</p>
 *
 * <p>Incognito home reuses this fragment: when launched with
 * {@code IS_INCOGNITO=true} the History row is repainted as Downloads
 * (icon + label + dispatched id), since incognito home has no
 * Downloads card and the History destination is irrelevant under
 * private browsing.</p>
 */
@AndroidEntryPoint
public class PopupHomeSheetDialogFragment extends BaseBottomSheetDialogFragment
        implements View.OnClickListener {

    private BrowserDialogViewModel mBrowserDialogViewModel;

    @Inject SharedPreferences mSharedPreferences;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBrowserDialogViewModel =
                new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_home_popup, container, false);

        bindRows();
        applyIncognitoSwap();
        applyQuitVisibility();

        return mView;
    }


    /**
     * Hooks every row in the popup. Settings and Quit use the shared
     * {@link #onClick(View)} since their LinearLayout id matches the
     * wire id the home fragment listens for. History has a specialized
     * listener because its dispatched id flips to Downloads under
     * incognito (see {@link #applyIncognitoSwap()}).
     */
    private void bindRows() {
        mView.findViewById(R.id.popup_settings).setOnClickListener(this);
        mView.findViewById(R.id.popup_quit).setOnClickListener(this);

        mView.findViewById(R.id.popup_history).setOnClickListener(view -> dispatch(
                mIsIncognito ? R.id.popup_downloads : R.id.popup_history));
    }


    /**
     * Repaints the History row as Downloads when the popup was
     * launched from incognito chrome. The view's id stays
     * {@code popup_history} — only the user-visible icon and label
     * flip; the dispatched OptionEntity id is set in
     * {@link #bindRows()} based on the same {@code mIsIncognito} flag.
     */
    private void applyIncognitoSwap() {
        if (!mIsIncognito) return;

        AppCompatImageView icon = mView.findViewById(R.id.popup_history_icon);
        TextView label = mView.findViewById(R.id.popup_history_label);
        if (icon == null || label == null) return;

        icon.setImageResource(R.drawable.download_24);
        label.setText(R.string.navigation_downloads);
    }


    /**
     * Toggles the destructive Quit card based on the user's "quit on
     * exit" preference. The card carries its own MaterialCard chrome
     * with error-tinted chip and label — same destructive quarantine
     * as the Browser popup.
     */
    private void applyQuitVisibility() {
        boolean quitEnabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF, false);
        View quitCard = mView.findViewById(R.id.popup_quit_card);
        if (quitCard != null) {
            quitCard.setVisibility(quitEnabled ? View.VISIBLE : View.GONE);
        }
    }


    @Override
    public void onClick(View view) {
        dispatch(view.getId());
    }


    /**
     * Central dispatch — dismisses the sheet and fires the option
     * event for the host fragment's handler. Used as both the shared
     * row click listener and by the History row's specialized
     * listener that sends a different id under incognito.
     */
    private void dispatch(int id) {
        OptionEntity entity = new OptionEntity();
        entity.setId(id);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_home_popup);
        mBrowserDialogViewModel.onOptionSelected(entity);
    }
}
