package com.solarized.firedown.phone.dialogs;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Home "more" bottom sheet.
 *
 * <p>Flat list of {@link TextView} rows at the
 * {@code Firedown.Widget.DialogOption} style, matching the dialog
 * vocabulary the rest of the app's popups (Downloads, Bookmarks list,
 * WebOption) already use. Carries only items without another surface
 * on Home: History (no card), Settings, and Quit when
 * {@link Preferences#SETTINGS_QUIT_PREF} is on.</p>
 *
 * <p>Incognito home reuses this fragment: when launched with
 * {@code IS_INCOGNITO=true} the History row's drawableStart icon,
 * label, and dispatched id all swap to Downloads, since incognito
 * home lacks a Downloads card and History is irrelevant under
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
     * Hooks every row. Settings and Quit use the shared
     * {@link #onClick(View)} since the view id matches the wire id
     * the home fragments listen for; History has a specialised
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
     * Repaints the History row as Downloads when launched from
     * incognito home. The row id stays {@code popup_history} — only
     * the inner label's drawableStart icon and text change; the
     * dispatched OptionEntity id is set in {@link #bindRows()} based
     * on the same {@code mIsIncognito} flag.
     */
    private void applyIncognitoSwap() {
        if (!mIsIncognito) return;
        TextView label = mView.findViewById(R.id.popup_history_text);
        if (label == null) return;
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.download_24, 0, 0, 0);
        label.setText(R.string.navigation_downloads);
    }


    /**
     * Toggles the destructive Quit row based on the user's "quit on
     * exit" preference. The row sits flush with Settings (no divider
     * above) and renders in colorPrimary so the brand-orange tint is
     * what marks it destructive — same treatment as the Downloads /
     * Bookmarks Delete row.
     */
    private void applyQuitVisibility() {
        boolean quitEnabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF, false);
        View quit = mView.findViewById(R.id.popup_quit);
        if (quit != null) quit.setVisibility(quitEnabled ? View.VISIBLE : View.GONE);
    }


    @Override
    public void onClick(View view) {
        dispatch(view.getId());
    }


    private void dispatch(int id) {
        OptionEntity entity = new OptionEntity();
        entity.setId(id);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_home_popup);
        mBrowserDialogViewModel.onOptionSelected(entity);
    }
}
