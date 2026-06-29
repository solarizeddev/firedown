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
 * <p>Mode-specific rows (visibility toggled by {@code IS_INCOGNITO}):
 * regular home shows History + Safe Folder; incognito home shows
 * Downloads instead (it has no Downloads card, and persisted History /
 * a separate Safe-Folder entry don't apply under private browsing —
 * incognito downloads already land in the Safe Folder).</p>
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
        applyModeVisibility();
        applyQuitVisibility();

        return mView;
    }


    /**
     * Hooks every row. Each row's view id is the wire id the home
     * fragments listen for, so they all share {@link #onClick(View)}.
     */
    private void bindRows() {
        mView.findViewById(R.id.popup_settings).setOnClickListener(this);
        mView.findViewById(R.id.popup_quit).setOnClickListener(this);
        mView.findViewById(R.id.popup_history).setOnClickListener(this);
        mView.findViewById(R.id.popup_vault).setOnClickListener(this);
        mView.findViewById(R.id.popup_downloads).setOnClickListener(this);

        // Fixed-meaning new-tab rows: New tab always opens a regular tab, New
        // private tab always incognito — in both modes. Their view ids differ
        // from the dispatched ids, so dispatch inline rather than through the
        // shared id-as-event onClick listener (same pattern as the Browser popup).
        mView.findViewById(R.id.popup_new_tab).setOnClickListener(v -> dispatch(R.id.new_tab));
        mView.findViewById(R.id.popup_new_incognito_tab)
                .setOnClickListener(v -> dispatch(R.id.new_incognito_tab));
    }


    /**
     * Shows the right per-mode rows for a consistent triplet above
     * Settings (+Quit):
     *   regular  : History + Safe Folder
     *   incognito: Downloads + History
     * History is common to both (it opens the saved regular-session
     * history read-only, same as bookmarks are shown under incognito).
     * Safe Folder is regular-only (incognito downloads already land in
     * the Safe Folder); Downloads is incognito-only (regular home has a
     * Downloads card).
     */
    private void applyModeVisibility() {
        toggle(R.id.popup_history, true);
        toggle(R.id.popup_vault, !mIsIncognito);
        toggle(R.id.popup_downloads, mIsIncognito);
    }

    private void toggle(int id, boolean visible) {
        View row = mView.findViewById(id);
        if (row != null) row.setVisibility(visible ? View.VISIBLE : View.GONE);
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
