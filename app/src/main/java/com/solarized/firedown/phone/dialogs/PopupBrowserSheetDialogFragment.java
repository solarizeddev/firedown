package com.solarized.firedown.phone.dialogs;


import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.browser.BackwardBrowserButton;
import com.solarized.firedown.ui.browser.BasicBrowserButton;
import com.solarized.firedown.ui.browser.ForwardBrowserButton;
import com.solarized.firedown.ui.browser.ReloadBrowserButton;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.WebUtils;

import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Browser "more" bottom sheet.
 *
 * <p>Flat list of {@link TextView} rows at the
 * {@code Firedown.Widget.DialogOption} style, matching the dialog
 * vocabulary the rest of the app's popups (Downloads, Bookmarks list,
 * WebOption) already use. Earlier iterations grouped everything into
 * MaterialCard sections with chip-style icons, which looked closer to
 * the Home dashboard cards than to an action menu.</p>
 *
 * <p>State-dependent UI lives here, not in the layout:</p>
 * <ul>
 *   <li><b>Bookmark page row</b> flips its drawableStart icon and
 *       label between outline/"Bookmark page" and filled/"Edit
 *       bookmark" based on {@code mHasBookmark}; the dispatched
 *       OptionEntity id swaps in lockstep.</li>
 *   <li><b>Vault row</b> swaps to Downloads in incognito (icon +
 *       label + dispatched id) — incognito chrome lacks a Downloads
 *       card and Vault deliberately doesn't surface from private
 *       browsing.</li>
 *   <li><b>Desktop site switch</b> mirrors the current page's
 *       {@code isDesktop()} on inflate.</li>
 *   <li><b>Quit row</b> stays GONE unless
 *       {@link Preferences#SETTINGS_QUIT_PREF} is on; rendered in the
 *       destructive .Final variant so the colour treatment matches
 *       Downloads' / Bookmarks' "Delete" row.</li>
 * </ul>
 */
@AndroidEntryPoint
public class PopupBrowserSheetDialogFragment extends BaseBottomSheetDialogFragment
        implements View.OnClickListener {

    private BrowserDialogViewModel mBrowserDialogViewModel;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private boolean mHasBookmark;
    private ReloadBrowserButton mReloadBrowserButton;
    private GeckoState mGeckoState;
    private AppCompatImageView mFavicon;
    private TextView mTitle;
    private TextView mHost;
    private String mDomain;
    private String mLastIconUrl;
    private String mLastTitle;
    private String mLastUri;

    @Inject SharedPreferences mSharedPreferences;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mReloadBrowserButton = null;
        mFavicon = null;
        mTitle = null;
        mHost = null;
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

        bindIdentity();
        bindQuickRow();
        bindRows();
        applyBookmarkState();
        applyIncognitoSwap();
        applyDesktopState();
        applyQuitVisibility();

        return mView;
    }


    /**
     * Populates the site-identity row: favicon, page title, hostname.
     * Mirrors the identity block the SecuritySheet uses on the same
     * browser surface so the two sheets read as siblings.
     */
    private void bindIdentity() {
        mTitle = mView.findViewById(R.id.popup_identity_title);
        mHost = mView.findViewById(R.id.popup_identity_host);
        mFavicon = mView.findViewById(R.id.popup_identity_favicon);

        // bg_popup_favicon resolves to ?attr/colorSurfaceVariant which
        // lands on a gray-blue tone — fine on the standard sheet bg
        // but a clash on the incognito sheet's purple container_high.
        // Tint the tile to the purple-family container_highest only
        // when incognito so the chip stays in family.
        if (mIsIncognito) {
            mFavicon.setBackgroundTintList(ColorStateList.valueOf(
                    IncognitoColors.getSurfaceContainerHighest(mFavicon.getContext(), true)));
        }

        mLastUri = mGeckoState.getEntityUri();
        mLastTitle = mGeckoState.getEntityTitle();
        mLastIconUrl = mGeckoState.getEntityIcon();
        mDomain = WebUtils.getDomainName(mLastUri);

        renderTitle();
        renderHost();
        loadFavicon();
    }


    /**
     * Paints the identity title TextView from the cached title.
     */
    private void renderTitle() {
        if (mTitle == null) return;
        mTitle.setText(mLastTitle);
    }


    /**
     * Paints the identity host TextView from the cached domain.
     */
    private void renderHost() {
        if (mHost != null) mHost.setText(mDomain);
    }


    /**
     * Renders the current {@code mGeckoState}'s favicon into the
     * identity row. Mirrors {@code SecurityStateSheetDialogFragment}'s
     * loader so the popup picks up the same icon resolution / rounded-
     * corner treatment, and short-circuits when the view is gone so
     * the live-update observer is safe to call after onDestroyView
     * fires.
     */
    private void loadFavicon() {
        if (mFavicon == null) return;
        int radius = getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        String fullDomain = TextUtils.isEmpty(mDomain)
                ? null
                : (mDomain.startsWith("http") ? mDomain : "https://" + mDomain);
        GlideHelper.load(mGeckoState.getEntityIcon(), fullDomain, mFavicon,
                RequestOptions.bitmapTransform(new RoundedCorners(radius)));
    }


    /**
     * Wires the top quick-row (Back / Forward / Share / Refresh).
     * Back and Forward inherit enabled-state from the gecko session.
     */
    private void bindQuickRow() {
        View headerView = mView.findViewById(R.id.popup_header);
        for (int i = 0; i < ((ViewGroup) headerView).getChildCount(); i++) {
            View v = ((ViewGroup) headerView).getChildAt(i);
            if (!(v instanceof BasicBrowserButton)) continue;

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
     * Hooks every list row. Most rows route through the shared
     * {@link #onClick(View)} since their view id matches the wire id
     * the BrowserFragment dispatcher listens for; Bookmark page and
     * Vault use specialised listeners because the dispatched id
     * depends on state (mHasBookmark / mIsIncognito).
     */
    private void bindRows() {
        mView.findViewById(R.id.popup_bookmarks).setOnClickListener(this);
        mView.findViewById(R.id.popup_find).setOnClickListener(this);
        mView.findViewById(R.id.popup_desktop).setOnClickListener(this);
        mView.findViewById(R.id.popup_history).setOnClickListener(this);
        mView.findViewById(R.id.popup_settings).setOnClickListener(this);
        mView.findViewById(R.id.popup_quit).setOnClickListener(this);

        mView.findViewById(R.id.popup_bookmark_page).setOnClickListener(view -> dispatch(
                mHasBookmark ? R.id.popup_bookmark_edit : R.id.popup_bookmark_add));

        mView.findViewById(R.id.popup_vault).setOnClickListener(view -> dispatch(
                mIsIncognito ? R.id.popup_downloads : R.id.popup_vault));

        // Fixed-meaning new-tab rows: New tab always opens a regular
        // tab, New private tab always incognito — in both modes. The
        // view ids differ from the dispatched ids, so dispatch inline
        // rather than through the shared id-as-event onClick listener.
        mView.findViewById(R.id.popup_new_tab).setOnClickListener(view -> dispatch(R.id.new_tab));
        mView.findViewById(R.id.popup_new_incognito_tab).setOnClickListener(view -> dispatch(R.id.new_incognito_tab));
    }


    /**
     * Paints the Bookmark page row's drawableStart icon and label
     * (on the inner TextView) to reflect whether the current page is
     * already saved. Click dispatch is wired to the same flag in
     * {@link #bindRows()}.
     */
    private void applyBookmarkState() {
        TextView label = mView.findViewById(R.id.popup_bookmark_page_text);
        if (label == null) return;
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                mHasBookmark ? R.drawable.ic_bookmark_24 : R.drawable.ic_bookmark_border_24,
                0, 0, 0);
        label.setText(mHasBookmark
                ? R.string.browser_menu_edit_bookmark
                : R.string.browser_menu_bookmark_this_page_2);
    }


    /**
     * Repaints the Vault row as Downloads when the popup was launched
     * from incognito chrome. The row id stays {@code popup_vault} —
     * only the inner label's drawableStart icon and text change; the
     * dispatched OptionEntity id is set in {@link #bindRows()} based
     * on the same {@code mIsIncognito} flag.
     */
    private void applyIncognitoSwap() {
        if (!mIsIncognito) return;
        TextView label = mView.findViewById(R.id.popup_vault_text);
        if (label == null) return;
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.download_24, 0, 0, 0);
        label.setText(R.string.navigation_downloads);
    }


    /**
     * Mirrors the current tab's Desktop-mode state into the row
     * switch. The whole row is the click target (handled by the
     * shared onClick → popup_desktop); the switch is decorative
     * status via duplicateParentState, not the only hit target.
     */
    private void applyDesktopState() {
        MaterialSwitch desktopSwitch = mView.findViewById(R.id.popup_desktop_switch);
        if (desktopSwitch == null) return;
        desktopSwitch.setChecked(mGeckoState.isDesktop());
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

        // Page identity (favicon, title, URL) resolves asynchronously —
        // when the popup opens immediately after a navigation,
        // GeckoSession's onPageTitleChange / onLocationChange /
        // onPageFavicon callbacks routinely land after bindIdentity
        // has already snapshotted whatever values were cached. Observe
        // the tabs LiveData (notified by GeckoStateDataRepository's
        // update* methods) and re-render whenever *this* entity's
        // title / URI / icon string actually changes. Other tab-list
        // events (new tab, close tab, sibling-tab updates) short-
        // circuit on the equality checks, keeping per-emission work
        // O(1). Same pattern SecuritySheet uses on the same surface.
        LiveData<List<GeckoStateEntity>> tabsLive = mIsIncognito
                ? mIncognitoStateViewModel.getTabs()
                : mGeckoStateViewModel.getTabs();
        tabsLive.observe(getViewLifecycleOwner(), tabs -> {
            if (tabs == null || mGeckoState == null) return;
            int id = mGeckoState.getEntityId();
            for (GeckoStateEntity entity : tabs) {
                if (entity.getId() != id) continue;

                String uri = entity.getUri();
                if (!Objects.equals(uri, mLastUri)) {
                    mLastUri = uri;
                    mGeckoState.setEntityUri(uri);
                    mDomain = WebUtils.getDomainName(uri);
                    renderHost();
                    renderTitle();
                }

                String title = entity.getTitle();
                if (!Objects.equals(title, mLastTitle)) {
                    mLastTitle = title;
                    mGeckoState.setEntityTitle(title);
                    renderTitle();
                }

                String icon = entity.getIcon();
                if (!Objects.equals(icon, mLastIconUrl)) {
                    mLastIconUrl = icon;
                    mGeckoState.setEntityIcon(icon);
                    loadFavicon();
                }
                break;
            }
        });
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();
        mHasBookmark = bundle != null && bundle.getBoolean(Keys.ITEM_BOOKMARK, false);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);

        mGeckoState = mIsIncognito
                ? mIncognitoStateViewModel.peekCurrentGeckoState()
                : mGeckoStateViewModel.peekCurrentGeckoState();
    }


    @Override
    public void onClick(View view) {
        dispatch(view.getId());
    }


    /**
     * Central dispatch — dismisses the sheet and fires the option event
     * for the BrowserFragment handler to act on. Used both as the
     * shared row click listener and by the specialised listeners
     * (Bookmark page, Vault) that need to send a different id than
     * their view's own.
     */
    private void dispatch(int id) {
        OptionEntity entity = new OptionEntity();
        entity.setId(id);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_popup);
        mBrowserDialogViewModel.onOptionSelected(entity);
    }
}
