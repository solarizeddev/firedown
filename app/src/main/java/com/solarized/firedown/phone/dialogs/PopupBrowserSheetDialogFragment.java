package com.solarized.firedown.phone.dialogs;


import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.TooltipCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.browser.BackwardBrowserButton;
import com.solarized.firedown.ui.browser.BasicBrowserButton;
import com.solarized.firedown.ui.browser.BookmarkBrowserButton;
import com.solarized.firedown.ui.browser.ForwardBrowserButton;
import com.solarized.firedown.ui.browser.QuickRowLabels;
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
 *   <li><b>Bookmark star</b> (in the quick-row header) flips its
 *       icon/label/tint between outline·"Bookmark" and filled·amber
 *       ·"Saved" based on {@code mHasBookmark}, and toggles the
 *       bookmark <i>in place</i> (add/delete on the shared
 *       {@link WebBookmarkViewModel}, whose repository is a singleton
 *       so the star reads the same source of truth as the Bookmarks
 *       library) with an Edit/Undo confirmation snackbar. Replaces the
 *       old buried "Bookmark page" list row.</li>
 *   <li><b>Quick-row labels</b> drop to icon-only past a width /
 *       font-scale breakpoint ({@link QuickRowLabels#iconOnly}); the
 *       five icons never move or shrink, so the star never re-hides.
 *       Every button keeps its label as contentDescription + tooltip.</li>
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
    private WebBookmarkViewModel mWebBookmarkViewModel;
    private boolean mHasBookmark;
    private ReloadBrowserButton mReloadBrowserButton;
    private BookmarkBrowserButton mStarButton;
    private boolean mIconOnly;
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
        mStarButton = null;
        mFavicon = null;
        mTitle = null;
        mHost = null;
    }


    /**
     * No dimen cap — the popup FILLS to just under the toolbar, the same sizing
     * the Captured-content holder uses, so the two sheets match height instead
     * of the popup hugging its rows (which left it shorter than Capture). The
     * fixed height is applied in {@link #onStart()}; the inner NestedScrollView
     * (weight 1) scrolls when the rows exceed the viewport.
     */
    @Override
    protected boolean isMaxHeightCapped() {
        return false;
    }

    /**
     * Size the sheet to the visible viewport minus the toolbar (mirrors
     * {@code BrowserOptionHolderSheetDialogFragment}), so the popup stops just
     * under the chrome and matches the Captured sheet's height. The pinned
     * header keeps its natural height and the weighted NestedScrollView fills
     * the rest. Run after super.onStart() (which clears the dimen cap and
     * expands the sheet).
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        sizePopupContent();
        // Width / font-scale may have changed (rotation) — re-decide whether
        // the quick-row shows labels or drops to icon-only, then repaint the
        // star so its label follows the same mode.
        applyQuickRowLabelMode();
        applyStarState();
    }

    /**
     * Size the {@code popup_content} child to {@code visibleRect.height() -
     * mActionBarSize} so the sheet sits flush just under the toolbar (no
     * overlap), matching the Captured holder. The FrameLayout root wraps this
     * child, so the sheet follows it; the base's onStart expands it and the
     * weighted NestedScrollView fills the space below the pinned header and
     * scrolls.
     */
    private void sizePopupContent() {
        if (mView == null || mActivity == null) return;
        View content = mView.findViewById(R.id.popup_content);
        if (content == null) return;
        ViewGroup.LayoutParams params = content.getLayoutParams();
        if (params == null) return;
        Rect visibleRect = new Rect();
        mActivity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleRect);
        int height = visibleRect.height() - mActionBarSize;
        if (height > 0) {
            params.height = height;
            content.setLayoutParams(params);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_browser_popup, container, false);
        sizePopupContent();

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
        applyQuickRowLabelMode();
        applyStarState();
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
     * Wires the top quick-row (Back / Forward / Bookmark★ / Share /
     * Refresh). Back and Forward inherit enabled-state from the gecko
     * session. Every button gets its label mirrored to a
     * contentDescription + long-press tooltip so the row stays
     * accessible when it drops to icon-only.
     *
     * <p>The bookmark star is the exception to the shared dispatch: the
     * other buttons dismiss the sheet and fire their id as an event
     * (via {@link #onClick}), but the star toggles the bookmark
     * <i>in place</i> and stays on the sheet, so it gets its own
     * listener and a stored reference for {@link #applyStarState()}.</p>
     */
    private void bindQuickRow() {
        View headerView = mView.findViewById(R.id.popup_header);
        for (int i = 0; i < ((ViewGroup) headerView).getChildCount(); i++) {
            View v = ((ViewGroup) headerView).getChildAt(i);
            if (!(v instanceof BasicBrowserButton button)) continue;

            // Label → accessibility. Read the button's own text now
            // (before icon-only mode may clear it) so TalkBack and the
            // long-press tooltip always announce the action.
            CharSequence label = button.getText();
            button.setContentDescription(label);
            TooltipCompat.setTooltipText(button, label);

            if (button instanceof BookmarkBrowserButton star) {
                // Toggles in place — must NOT go through the shared
                // dispatch (which dismisses the sheet).
                mStarButton = star;
                star.setOnClickListener(view -> onBookmarkStarClicked());
                continue;
            }

            button.setOnClickListener(this);

            if (button instanceof ReloadBrowserButton) {
                mReloadBrowserButton = (ReloadBrowserButton) button;
            } else if (button instanceof BackwardBrowserButton backward) {
                backward.setClickable(mGeckoState.canGoBackward());
                backward.setEnabled(mGeckoState.canGoBackward());
            } else if (button instanceof ForwardBrowserButton forward) {
                forward.setClickable(mGeckoState.canGoForward());
                forward.setEnabled(mGeckoState.canGoForward());
            }
        }
    }


    /**
     * Hooks every list row. Most rows route through the shared
     * {@link #onClick(View)} since their view id matches the wire id
     * the BrowserFragment dispatcher listens for; Vault uses a
     * specialised listener because the dispatched id depends on state
     * (mIsIncognito). Bookmarking is no longer a list row — it moved
     * to the quick-row star (see {@link #bindQuickRow()}).
     */
    private void bindRows() {
        mView.findViewById(R.id.popup_bookmarks).setOnClickListener(this);
        mView.findViewById(R.id.popup_find).setOnClickListener(this);
        mView.findViewById(R.id.popup_save_snapshot).setOnClickListener(this);
        mView.findViewById(R.id.popup_desktop).setOnClickListener(this);
        mView.findViewById(R.id.popup_history).setOnClickListener(this);
        mView.findViewById(R.id.popup_settings).setOnClickListener(this);
        mView.findViewById(R.id.popup_quit).setOnClickListener(this);

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
     * Decides whether the quick-row shows labels or drops to icon-only
     * for the CURRENT configuration (width + font scale) and applies it
     * to the four static buttons. The star is repainted separately in
     * {@link #applyStarState()} (its label is state-dependent), but it
     * reads the same {@link #mIconOnly} flag computed here.
     *
     * <p>Icon-only = clear the text and center the icon; labelled =
     * restore the text with the icon stacked above it (textTop). The
     * icon itself never moves or resizes, so the row stays a single
     * non-scrolling line and the star keeps its slot at every size.</p>
     */
    private void applyQuickRowLabelMode() {
        Configuration config = getResources().getConfiguration();
        mIconOnly = QuickRowLabels.iconOnly(config.screenWidthDp, config.fontScale);

        View headerView = mView.findViewById(R.id.popup_header);
        if (headerView == null) return;
        for (int i = 0; i < ((ViewGroup) headerView).getChildCount(); i++) {
            View v = ((ViewGroup) headerView).getChildAt(i);
            if (!(v instanceof BasicBrowserButton button)) continue;
            // Star is state-dependent; applyStarState() owns its text.
            if (button instanceof BookmarkBrowserButton) continue;
            applyButtonLabelMode(button, button.getContentDescription());
        }
    }


    /**
     * Applies the current label mode to one quick-row button: in
     * icon-only mode the text is cleared and the icon centres
     * (ICON_GRAVITY_TEXT_START, no icon padding); otherwise the label
     * is restored under the icon (ICON_GRAVITY_TEXT_TOP). The
     * accessible name is carried by the contentDescription set in
     * {@link #bindQuickRow()}, so clearing the visible text never
     * removes the button's meaning.
     */
    private void applyButtonLabelMode(MaterialButton button, CharSequence label) {
        if (mIconOnly) {
            button.setText(null);
            button.setIconPadding(0);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        } else {
            button.setText(label);
            button.setIconPadding(getResources().getDimensionPixelSize(R.dimen.quick_row_icon_padding));
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        }
    }


    /**
     * Paints the bookmark star to reflect whether the current page is
     * already saved: filled + amber (colorPrimary) + "Saved" when
     * bookmarked, outline + default tint + "Bookmark" otherwise. The
     * label is only shown when the row is in labelled mode (icon-only
     * clears it, same as the other buttons); the accessible name and
     * tooltip always follow the state.
     */
    private void applyStarState() {
        if (mStarButton == null) return;
        CharSequence label = getString(mHasBookmark
                ? R.string.browser_menu_bookmark_saved_short
                : R.string.browser_menu_bookmark_short);

        mStarButton.setIconResource(mHasBookmark
                ? R.drawable.ic_bookmark_24
                : R.drawable.ic_bookmark_border_24);
        // Amber accent when saved so the filled star reads as "on";
        // default header selector (onSurfaceVariant) when unsaved so it
        // sits level with the sibling icons.
        ColorStateList tint = mHasBookmark
                ? ColorStateList.valueOf(IncognitoColors.getPrimary(mStarButton.getContext(), mIsIncognito))
                : AppCompatResources.getColorStateList(mStarButton.getContext(), R.color.popup_header_selector);
        mStarButton.setIconTint(tint);
        mStarButton.setTextColor(tint);

        mStarButton.setContentDescription(label);
        TooltipCompat.setTooltipText(mStarButton, label);
        applyButtonLabelMode(mStarButton, label);
    }


    /**
     * Toggles the bookmark for the current page IN PLACE — the sheet
     * stays open, the star repaints, and a confirmation snackbar offers
     * Edit (when just saved) or Undo (when just removed). add/delete go
     * through the shared singleton repository, so the star and the
     * Bookmarks library never diverge.
     */
    private void onBookmarkStarClicked() {
        if (mGeckoState == null || mWebBookmarkViewModel == null) return;
        if (mHasBookmark) {
            mWebBookmarkViewModel.delete(
                    WebBookmarkDataRepository.bookmarkIdFor(mGeckoState.getEntityUri()));
            mHasBookmark = false;
            applyStarState();
            showBookmarkSnackbar(R.string.browser_bookmark_removed, R.string.undo,
                    this::undoRemoveBookmark);
        } else {
            mWebBookmarkViewModel.add(mGeckoState);
            mHasBookmark = true;
            applyStarState();
            showBookmarkSnackbar(R.string.browser_bookmark_saved_toast, R.string.edit,
                    () -> dispatch(R.id.popup_bookmark_edit));
        }
    }


    /**
     * Undo for a just-removed bookmark: re-add and repaint the star.
     * No follow-up snackbar (avoids a toast loop) — the star flipping
     * back to filled·"Saved" is confirmation enough.
     */
    private void undoRemoveBookmark() {
        if (mGeckoState == null || mWebBookmarkViewModel == null) return;
        mWebBookmarkViewModel.add(mGeckoState);
        mHasBookmark = true;
        applyStarState();
    }


    /**
     * Shows the bookmark confirmation snackbar inside the sheet (the
     * sheet stays open on a star tap). Anchored to the sheet's own
     * view, tinted to match the incognito surface when needed — the
     * same treatment the download snackbars on this surface use.
     */
    private void showBookmarkSnackbar(@StringRes int message, @StringRes int action,
                                      Runnable onAction) {
        if (mView == null) return;
        Snackbar snackbar = Snackbar.make(mView, message, Snackbar.LENGTH_LONG)
                .setAction(action, v -> onAction.run());
        if (mIsIncognito) {
            snackbar.setTextColor(IncognitoColors.getOnSurface(mActivity, true))
                    .setBackgroundTint(IncognitoColors.getSurface(mActivity, true))
                    .setActionTextColor(IncognitoColors.getPrimary(mActivity, true));
        }
        snackbar.show();
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
                    // A same-tab (SPA) navigation changed the page under the
                    // open sheet — recompute the star's saved-state for the new
                    // URL so it doesn't act on the previous page's bookmark.
                    if (mWebBookmarkViewModel != null) {
                        mHasBookmark = mWebBookmarkViewModel.contains(mGeckoState);
                        applyStarState();
                    }
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
        // The star toggles the bookmark in place. Its repository is a
        // @Singleton, so this instance shares state with BrowserFragment's —
        // the star's saved-state and the Bookmarks library never diverge.
        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);

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
