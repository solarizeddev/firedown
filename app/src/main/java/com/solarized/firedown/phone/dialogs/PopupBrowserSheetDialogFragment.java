package com.solarized.firedown.phone.dialogs;


import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import java.util.function.Consumer;

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
 *   <li><b>Two icon rows</b> — controls (Back/Forward/Bookmark★/Refresh)
 *       and page actions (Find/Desktop/Share/Save) — share one icon-only
 *       fit decision: a pre-layout estimate ({@link QuickRowLabels#iconOnly},
 *       width + font scale) picks the initial mode, then a post-measure
 *       check ({@link #scheduleQuickRowFitCheck()}) drops BOTH rows to
 *       icon-only if any TRANSLATED label would still ellipsize at its real
 *       column width. Icons never move, so the star never re-hides; every
 *       button keeps its full name as contentDescription + tooltip.</li>
 *   <li><b>Vault row</b> swaps to Downloads in incognito (icon +
 *       label + dispatched id) — incognito chrome lacks a Downloads
 *       card and Vault deliberately doesn't surface from private
 *       browsing.</li>
 *   <li><b>Desktop icon</b> (page-actions row) highlights in the accent
 *       tint when the tab is in desktop mode — see
 *       {@link #applyDesktopState()}.</li>
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
     * The popup is CAPPED, but not to the shared 640dp dimen — see
     * {@link #resolveMaxHeightPx()}.
     *
     * <p>This returned false (no cap at all) after the sheet was aligned with
     * the Capture sheet, which opts out. That was the wrong half to copy: the
     * Capture sheet opts out because it sets its OWN fixed inner height, so
     * "no behaviour cap" still leaves it bounded. The popup hugs its content,
     * so no cap meant genuinely unbounded — at a large font scale with the Quit
     * row shown it could grow past the toolbar and fill the viewport.
     */
    @Override
    protected boolean isMaxHeightCapped() {
        return true;
    }


    /**
     * Cap the popup at exactly the height the Capture sheet occupies —
     * everything below the toolbar — rather than the shared 640dp dimen.
     *
     * <p>The popup HUGS its content (the layout is wrap_content top to bottom),
     * so this is a ceiling and never a floor: a short two-row menu still opens
     * short. It only bites when the content would otherwise run past the
     * toolbar, and past it the NestedScrollView scrolls under the pinned
     * identity header.
     *
     * <p>Matches {@code BrowserOptionHolderSheetDialogFragment}, which sizes
     * its inner frame to {@code visibleRect.height() - actionBarSize -
     * topMargin} — that frame's top margin is the drag-handle clearance INSIDE
     * the sheet, so the sheet's own total there is {@code visibleRect.height()
     * - actionBarSize}, which is what a behaviour max-height measures.
     *
     * <p>The rect is read fresh on every call rather than cached at create
     * time, so rotation needs no cached-width/height swap (the Capture sheet
     * carries one because it caches); the base re-resolves this from both
     * {@code onStart} and {@code onConfigurationChanged}. Falls back to the
     * shared dimen if the window isn't reachable yet.
     */
    @Override
    protected int resolveMaxHeightPx() {
        if (mActivity == null || mActivity.getWindow() == null) {
            return super.resolveMaxHeightPx();
        }
        Rect visibleRect = new Rect();
        mActivity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleRect);
        // app_bar_size read fresh rather than via the cached mActionBarSize
        // field: that field is stamped in onCreate, which does NOT re-run on
        // rotation (the activities declare configChanges), so a future
        // values-land variant of the dimen would silently go stale here.
        int actionBarSize = getResources().getDimensionPixelSize(R.dimen.app_bar_size);
        int cap = visibleRect.height() - actionBarSize;
        return cap > 0 ? cap : super.resolveMaxHeightPx();
    }


    @Override
    public void onStart() {
        super.onStart();
        applyContentMaxHeight();
    }


    /**
     * Enforce the ceiling on {@code popup_content} itself, which is what
     * actually bounds this sheet — {@link #resolveMaxHeightPx()} only caps the
     * BottomSheetBehavior wrapper, and that alone did not work: a capped wrapper
     * around a wrap_content child leaves the child measuring at its full natural
     * height, so the sheet still rendered full-screen (reported on-device).
     * Sizing the content child is the mechanism the earlier fixed-height version
     * used, and it is the one the Capture sheet uses too
     * ({@code BrowserOptionHolderSheetDialogFragment} sets {@code content_frame}'s
     * layout height); the wrapper cap is kept as an outer guard.
     *
     * <p>The ONE difference from that fixed-height version — and the whole point
     * here — is that the height is applied CONDITIONALLY. The natural content
     * height is measured first, and the cap is written only when the content
     * actually exceeds it; otherwise the child is returned to WRAP_CONTENT. So a
     * short menu opens short and there is no minimum, while a long one stops
     * exactly where the Capture sheet stops and scrolls inside (see the
     * weighted NestedScrollView in the layout).
     *
     * <p>Deferred to {@code post} because the natural height can only be
     * measured once the view has a width. Re-run on configuration change, and
     * the measure is independent of the currently-applied height, so it can
     * flip back from clamped to WRAP_CONTENT when rotation gives more room.
     */
    private void applyContentMaxHeight() {
        if (mView == null) {
            return;
        }
        final View content = mView.findViewById(R.id.popup_content);
        if (content == null) {
            return;
        }
        content.post(() -> {
            if (content.getWidth() <= 0 || !isAdded()) {
                return;
            }
            int cap = resolveMaxHeightPx();
            if (cap <= 0) {
                return;
            }
            // Measure the content's NATURAL height: an UNSPECIFIED height spec
            // ignores whatever layout height is currently applied, so a clamped
            // sheet still reports what it would like to be.
            content.measure(
                    View.MeasureSpec.makeMeasureSpec(content.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int natural = content.getMeasuredHeight();
            int target = natural > cap ? cap : ViewGroup.LayoutParams.WRAP_CONTENT;
            ViewGroup.LayoutParams params = content.getLayoutParams();
            if (params != null && params.height != target) {
                params.height = target;
                content.setLayoutParams(params);
            }
        });
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyQuickRowLabelMode();
        applyStarState();
        applyContentMaxHeight();
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
     * Wires BOTH quick-row icon rows: the controls row (Back / Forward /
     * Bookmark★ / Refresh) and the page-actions row (Find / Desktop / Share /
     * Save). They read as one action block and share the icon-only fit logic.
     *
     * <p>Each button's SHORT visible label is stashed in a tag (so the
     * icon-only ↔ labelled toggle can restore it), and its FULL name —
     * the {@code contentDescription} declared in XML for the row-2 buttons,
     * or the label itself for row 1 — becomes the TalkBack name + long-press
     * tooltip, so dropping the visible text never drops meaning.</p>
     *
     * <p>Dispatch: every button fires its id via {@link #onClick} (dismissing
     * the sheet) EXCEPT the bookmark star, which toggles the bookmark
     * <i>in place</i> and keeps the sheet open — so it gets its own listener
     * and a stored reference for {@link #applyStarState()}. Row-1 nav buttons
     * additionally carry the gecko session's back/forward enabled-state.</p>
     */
    private void bindQuickRow() {
        // Accessibility + restore-label for every button in both rows.
        forEachQuickRowButton(button -> {
            CharSequence shortLabel = button.getText();
            button.setTag(R.id.quick_row_label, shortLabel);
            CharSequence accessible = button.getContentDescription();
            if (accessible == null) accessible = shortLabel;
            button.setContentDescription(accessible);
            TooltipCompat.setTooltipText(button, accessible);
        });

        // Row 1 — controls: star (in place), nav enabled-state, reload ref.
        View header = mView.findViewById(R.id.popup_header);
        if (header instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View v = group.getChildAt(i);
                if (!(v instanceof BasicBrowserButton button)) continue;
                if (button instanceof BookmarkBrowserButton star) {
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

        // Row 2 — page actions: plain buttons that dispatch their id (same
        // contract the old Find/Desktop/Share/Save text rows had).
        View actions = mView.findViewById(R.id.popup_actions_row);
        if (actions instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View v = group.getChildAt(i);
                if (v instanceof MaterialButton button) button.setOnClickListener(this);
            }
        }
    }


    /**
     * Runs {@code fn} over every {@link MaterialButton} in both quick-row
     * containers (controls row + page-actions row). Central so the label /
     * accessibility / fit passes all iterate the same set.
     */
    private void forEachQuickRowButton(Consumer<MaterialButton> fn) {
        forEachButtonIn(R.id.popup_header, fn);
        forEachButtonIn(R.id.popup_actions_row, fn);
    }

    private void forEachButtonIn(int containerId, Consumer<MaterialButton> fn) {
        View c = mView == null ? null : mView.findViewById(containerId);
        if (!(c instanceof ViewGroup group)) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v instanceof MaterialButton button) fn.accept(button);
        }
    }


    /**
     * Hooks every list row. Most rows route through the shared
     * {@link #onClick(View)} since their view id matches the wire id
     * the BrowserFragment dispatcher listens for; Vault uses a
     * specialised listener because the dispatched id depends on state
     * (mIsIncognito). Bookmarking is the quick-row star, and the page
     * actions (Find / Desktop / Share / Save) are the page-actions icon
     * row — both wired in {@link #bindQuickRow()}, not here.
     */
    private void bindRows() {
        mView.findViewById(R.id.popup_bookmarks).setOnClickListener(this);
        mView.findViewById(R.id.popup_history).setOnClickListener(this);
        mView.findViewById(R.id.popup_sync).setOnClickListener(this);
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
     * Decides whether the two icon rows show labels or drop to icon-only
     * for the CURRENT configuration (width + font scale) and applies it to
     * every static button in both rows. The star is repainted separately in
     * {@link #applyStarState()} (its label is state-dependent), but it reads
     * the same {@link #mIconOnly} flag computed here.
     *
     * <p>Icon-only = clear the text and center the icon; labelled =
     * restore the short label with the icon stacked above it (textTop). The
     * icons never move or resize, so each row stays a single non-scrolling
     * line and the star keeps its slot at every size.</p>
     */
    private void applyQuickRowLabelMode() {
        Configuration config = getResources().getConfiguration();
        // Pre-layout estimate — picks the initial mode with no flicker.
        mIconOnly = QuickRowLabels.iconOnly(config.screenWidthDp, config.fontScale);
        applyLabelModeToButtons();
        // If the estimate kept labels, verify they actually FIT the real
        // (translated) column widths once laid out; a locale with wider
        // labels than the English calibration drops the whole row to
        // icon-only rather than truncating.
        if (!mIconOnly) scheduleQuickRowFitCheck();
    }


    /**
     * Applies the current {@link #mIconOnly} mode to every static quick-row
     * button across BOTH icon rows. The star is handled by
     * {@link #applyStarState()} (its label is state-dependent), so it's
     * skipped here.
     */
    private void applyLabelModeToButtons() {
        forEachQuickRowButton(button -> {
            if (button instanceof BookmarkBrowserButton) return;
            applyButtonLabelMode(button);
        });
    }


    /**
     * After the quick-row is laid out, drops the whole row to icon-only if
     * ANY label ellipsizes at its real (translated) column width — the
     * pre-layout estimate is calibrated for English, so this is what makes
     * wider locales degrade cleanly instead of showing "Actuali…". Uses a
     * one-shot pre-draw listener and returns {@code false} on the switch so
     * the row re-lays-out with cleared labels BEFORE the first draw (no
     * label flicker).
     */
    private void scheduleQuickRowFitCheck() {
        View header = mView.findViewById(R.id.popup_header);
        if (header == null) return;
        header.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        View h = mView == null ? null : mView.findViewById(R.id.popup_header);
                        if (h != null) {
                            h.getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        if (mView == null || mIconOnly) return true;
                        if (anyQuickRowLabelTruncated()) {
                            mIconOnly = true;
                            applyLabelModeToButtons();
                            applyStarState();
                            return false; // reflow before drawing
                        }
                        return true;
                    }
                });
    }


    /**
     * @return true if any quick-row button's label is ellipsized at its
     *         current width (i.e. the labels don't fit and the row should
     *         go icon-only). Reads {@link Layout#getEllipsisCount}, so it's
     *         only meaningful after layout.
     */
    private boolean anyQuickRowLabelTruncated() {
        return anyLabelTruncatedIn(R.id.popup_header)
                || anyLabelTruncatedIn(R.id.popup_actions_row);
    }

    private boolean anyLabelTruncatedIn(int containerId) {
        View c = mView == null ? null : mView.findViewById(containerId);
        if (!(c instanceof ViewGroup group)) return false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (!(v instanceof MaterialButton button)) continue;
            Layout layout = button.getLayout();
            if (layout == null) continue;
            for (int line = 0; line < layout.getLineCount(); line++) {
                if (layout.getEllipsisCount(line) > 0) return true;
            }
        }
        return false;
    }


    /**
     * Applies the current label mode to one quick-row button: in
     * icon-only mode the text is cleared and the icon centres
     * (ICON_GRAVITY_TEXT_START); otherwise the SHORT visible label is
     * restored from the button's tag under the icon (ICON_GRAVITY_TEXT_TOP).
     * The accessible name is carried by the contentDescription set in
     * {@link #bindQuickRow()}, so clearing the visible text never removes
     * the button's meaning. The icon↔label gap (style iconPadding) is left
     * as-is in both modes — with no text, it only shifts every icon by the
     * same ~2dp, which is invisible.
     */
    private void applyButtonLabelMode(MaterialButton button) {
        if (mIconOnly) {
            button.setText(null);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        } else {
            Object tag = button.getTag(R.id.quick_row_label);
            button.setText(tag instanceof CharSequence ? (CharSequence) tag : null);
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

        // The star's label is state-dependent, so refresh its restore-tag +
        // accessible name before (re)applying the label mode.
        mStarButton.setTag(R.id.quick_row_label, label);
        mStarButton.setContentDescription(label);
        TooltipCompat.setTooltipText(mStarButton, label);
        applyButtonLabelMode(mStarButton);
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
     * Paints the Desktop icon (in the page-actions row) to reflect the
     * current tab's Desktop-mode state: amber accent (colorPrimary) when on,
     * default header tint when off — the same active-state treatment the
     * bookmark star uses. Tapping still dispatches {@code popup_desktop}
     * (toggle + reload, dismissing the sheet), so this only reflects the
     * state the sheet opened with. Independent of the label mode, so it
     * survives an icon-only switch.
     */
    private void applyDesktopState() {
        View v = mView.findViewById(R.id.popup_desktop);
        if (!(v instanceof MaterialButton desktop) || mGeckoState == null) return;
        ColorStateList tint = mGeckoState.isDesktop()
                ? ColorStateList.valueOf(IncognitoColors.getPrimary(desktop.getContext(), mIsIncognito))
                : AppCompatResources.getColorStateList(desktop.getContext(), R.color.popup_header_selector);
        desktop.setIconTint(tint);
        desktop.setTextColor(tint);
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
