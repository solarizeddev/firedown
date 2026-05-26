package com.solarized.firedown.phone.dialogs;


import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.browser.BackwardBrowserButton;
import com.solarized.firedown.ui.browser.BasicBrowserButton;
import com.solarized.firedown.ui.browser.BookmarkBrowserButton;
import com.solarized.firedown.ui.browser.ForwardBrowserButton;
import com.solarized.firedown.ui.browser.ReloadBrowserButton;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Browser "more" bottom sheet.
 *
 * <p>Replaces the previous RecyclerView-of-rows with a static layout
 * of MaterialCard sections (page state · library · app · destructive).
 * The structure is intentionally inline rather than data-driven: the
 * row count is small enough (5–7 items, depending on mode and the
 * quit-on-exit preference) that the section-grouping is easier to
 * read in XML than to assemble from arrays.</p>
 *
 * <p>State-dependent UI lives here, not in the layout:</p>
 * <ul>
 *   <li><b>Bookmark star</b> in the quick-row flips its icon and the
 *       OptionEntity it dispatches (add vs edit) based on
 *       {@code mHasBookmark}.</li>
 *   <li><b>Vault row</b> swaps to Downloads in incognito mode — chip
 *       background, icon tint, label, and dispatched id all change so
 *       incognito chrome reaches Downloads (no Downloads card there)
 *       without surfacing the Vault entrypoint at all.</li>
 *   <li><b>Desktop site switch</b> mirrors the current page's
 *       {@code isDesktop()} on inflate.</li>
 *   <li><b>Quit card</b> stays GONE unless {@link Preferences#SETTINGS_QUIT_PREF}
 *       is on; rendered in its own MaterialCard with error-tinted chip
 *       so the destructive action is visually quarantined.</li>
 * </ul>
 */
@AndroidEntryPoint
public class PopupBrowserSheetDialogFragment extends BaseBottomSheetDialogFragment
        implements View.OnClickListener {

    private BrowserDialogViewModel mBrowserDialogViewModel;
    private boolean mHasBookmark;
    private ReloadBrowserButton mReloadBrowserButton;
    private BookmarkBrowserButton mBookmarkBrowserButton;
    private GeckoState mGeckoState;

    @Inject SharedPreferences mSharedPreferences;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mReloadBrowserButton = null;
        mBookmarkBrowserButton = null;
    }


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_browser_popup, container, false);

        // Guard: peekCurrentGeckoState can return null if the popup was
        // opened in an inconsistent state (process restoration, tab
        // closed externally). Dismiss rather than NPE.
        if (mGeckoState == null) {
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_popup);
            return mView;
        }

        bindQuickRow();
        bindRows();
        applyIncognitoSwap();
        applyDesktopState();
        applyQuitVisibility();

        return mView;
    }


    /**
     * Wires the top quick-row (Back / Forward / Bookmark / Share / Refresh).
     * Back and Forward inherit enabled-state from the gecko session; the
     * Bookmark star also flips its icon and label here based on
     * {@code mHasBookmark}.
     */
    private void bindQuickRow() {
        View headerView = mView.findViewById(R.id.popup_header);
        for (int i = 0; i < ((ViewGroup) headerView).getChildCount(); i++) {
            View v = ((ViewGroup) headerView).getChildAt(i);
            if (!(v instanceof BasicBrowserButton)) continue;

            if (v instanceof BookmarkBrowserButton bookmarkButton) {
                mBookmarkBrowserButton = bookmarkButton;
                applyBookmarkState();
                // Direct dispatch — popup_bookmark_add / popup_bookmark_edit
                // are the wire ids the BrowserFragment handler listens to,
                // not the popup_bookmark slot id from XML.
                bookmarkButton.setOnClickListener(view -> dispatch(
                        mHasBookmark ? R.id.popup_bookmark_edit : R.id.popup_bookmark_add));
                continue;
            }

            v.setOnClickListener(this);

            if (v instanceof ReloadBrowserButton) {
                mReloadBrowserButton = (ReloadBrowserButton) v;
            } else if (v instanceof BackwardBrowserButton backward) {
                backward.setClickable(mGeckoState.canGoBackward());
                backward.setEnabled(mGeckoState.canGoBackward());
            } else if (v instanceof ForwardBrowserButton forward) {
                forward.setClickable(mGeckoState.canGoForward());
                forward.setEnabled(mGeckoState.canGoForward());
            }
        }
    }


    /**
     * Updates the bookmark quick-row button's icon and label according
     * to {@code mHasBookmark}. Called from {@link #bindQuickRow()} on
     * inflate. If the popup ever needed to live-update from a bookmark
     * event, this method is the hook.
     */
    private void applyBookmarkState() {
        if (mBookmarkBrowserButton == null) return;
        mBookmarkBrowserButton.setIconResource(
                mHasBookmark ? R.drawable.ic_bookmark_24 : R.drawable.ic_bookmark_border_24);
        mBookmarkBrowserButton.setText(getString(
                mHasBookmark ? R.string.browser_menu_bookmark_saved_short
                             : R.string.browser_menu_bookmark_short));
    }


    /**
     * Hooks every list row in the body. Each row is a LinearLayout whose
     * own id matches the wire id expected by the BrowserFragment
     * dispatcher, so a single shared {@link #onClick(View)} can route
     * by {@code view.getId()}.
     */
    private void bindRows() {
        mView.findViewById(R.id.popup_find).setOnClickListener(this);
        mView.findViewById(R.id.popup_desktop).setOnClickListener(this);
        mView.findViewById(R.id.popup_bookmarks).setOnClickListener(this);
        mView.findViewById(R.id.popup_history).setOnClickListener(this);
        mView.findViewById(R.id.popup_settings).setOnClickListener(this);
        mView.findViewById(R.id.popup_quit).setOnClickListener(this);

        // Vault is wired separately because its dispatched id depends on
        // incognito mode (see applyIncognitoSwap).
        mView.findViewById(R.id.popup_vault).setOnClickListener(view -> dispatch(
                mIsIncognito ? R.id.popup_downloads : R.id.popup_vault));
    }


    /**
     * Repaints the Vault row to read as Downloads when this popup was
     * launched from incognito chrome. The view's id stays
     * {@code popup_vault} — it's just the user-visible chip, icon, and
     * label that flip; the dispatched OptionEntity id is set in
     * {@link #bindRows()} based on the same {@code mIsIncognito} flag.
     */
    private void applyIncognitoSwap() {
        if (!mIsIncognito) return;

        FrameLayout chip = mView.findViewById(R.id.popup_vault_chip);
        AppCompatImageView icon = mView.findViewById(R.id.popup_vault_icon);
        TextView label = mView.findViewById(R.id.popup_vault_label);
        if (chip == null || icon == null || label == null) return;

        chip.setBackgroundResource(R.drawable.bg_popup_chip_neutral);
        icon.setImageResource(R.drawable.download_24);
        int neutralTint = MaterialColors.getColor(icon,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(neutralTint));
        label.setText(R.string.navigation_downloads);
    }


    /**
     * Mirrors the current tab's Desktop-mode state into the row switch
     * and forwards taps on the switch through the same dispatcher used
     * for the row itself. Clicking the row chrome also flips the state
     * (handled by the row's shared onClick → popup_desktop), so the
     * whole row is one tappable surface — the switch is decorative
     * status, not the only hit target.
     */
    private void applyDesktopState() {
        MaterialSwitch desktopSwitch = mView.findViewById(R.id.popup_desktop_switch);
        if (desktopSwitch == null) return;
        desktopSwitch.setChecked(mGeckoState.isDesktop());
    }


    /**
     * Toggles the destructive Quit card based on the user's "quit on
     * exit" preference. The card carries its own MaterialCard chrome
     * with error-tinted chip and label — visually quarantined so the
     * action can't be hit by accident when scanning the menu.
     */
    private void applyQuitVisibility() {
        boolean quitEnabled = mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF, false);
        View quitCard = mView.findViewById(R.id.popup_quit_card);
        if (quitCard != null) {
            quitCard.setVisibility(quitEnabled ? View.VISIBLE : View.GONE);
        }
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Change the reload-button icon when a page is loading so the
        // user can hit it as a stop button. Bound after view creation so
        // the observer's lifecycle matches the view, not the fragment.
        mBrowserDialogViewModel.getLoadingEvent().observe(getViewLifecycleOwner(), loading -> {
            if (mReloadBrowserButton != null) {
                mReloadBrowserButton.setLoading(loading);
            }
        });
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();
        mHasBookmark = bundle != null && bundle.getBoolean(Keys.ITEM_BOOKMARK, false);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);

        GeckoStateViewModel geckoStateViewModel =
                new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        IncognitoStateViewModel incognitoStateViewModel =
                new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);

        if (mIsIncognito) {
            mGeckoState = incognitoStateViewModel.peekCurrentGeckoState();
        } else {
            mGeckoState = geckoStateViewModel.peekCurrentGeckoState();
        }
    }


    @Override
    public void onClick(View view) {
        dispatch(view.getId());
    }


    /**
     * Central dispatch — dismisses the sheet and fires the option event
     * for the BrowserFragment handler to act on. Used both as the shared
     * row click listener and by the specialized listeners (Bookmark
     * star, Vault row) that need to send a different id than their
     * view's own.
     */
    private void dispatch(int id) {
        OptionEntity entity = new OptionEntity();
        entity.setId(id);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_popup);
        mBrowserDialogViewModel.onOptionSelected(entity);
    }
}
