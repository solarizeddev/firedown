package com.solarized.firedown.geckoview;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.ui.IncognitoColors;


public class GeckoToolbar extends FrameLayout implements View.OnClickListener, View.OnKeyListener {

    private static final String TAG = GeckoToolbar.class.getName();

    private static final int DURATION = 150;

    private boolean mSearchMode;

    private OnToolbarListener mOnToolbarListener;

    private OnClearFocusListener mOnClearFocusListener;

    private GeckoProgressBar mGeckoProgressBar;

    private View mSearchUpButton;

    private View mSearchDownButton;

    private TextView mSearchText;

    private AutoCompleteEditText mEditText;

    private MaterialButton mClearButton;

    private MaterialButton mAddressBarButton;

    private MaterialButton mReloadButton;

    private AppCompatImageView mBackground;

    private boolean mHomeEnabled;

    private boolean mTrackingEnabled;

    private boolean mAdsEnabled = true;

    /** Last drawable id pushed to the shield button — used to skip
     *  no-op icon swaps so we don't re-trigger the transition animation
     *  every time {@link #updateShieldIcon()} is called on incidental
     *  state pokes (e.g. URL change with identical protection state). */
    private int mCurrentShieldRes;

    private boolean mSecureEnabled;

    private boolean mClearButtonEnabled;

    private int mSearchTextDefaultColor;

    private int mAnimColorFrom;

    private int mAnimColorTo;

    public interface OnClearFocusListener {
        void onToolbarClearFocus();
    }

    public interface OnToolbarListener {
        void onToolbarButtonClick(View v, int id);
        void onToolbarKey(int keyCode, KeyEvent event);
    }


    public GeckoToolbar(Context context) {
        super(context);
        init(context, null, 0);
    }

    public GeckoToolbar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public GeckoToolbar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    @Override
    public void onClick(View v) {
        if (mOnToolbarListener != null) mOnToolbarListener.onToolbarButtonClick(v, v.getId());
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mOnToolbarListener != null) mOnToolbarListener.onToolbarKey(keyCode, event);
            return mSearchMode && keyCode == KeyEvent.KEYCODE_BACK;
        }

        return false;
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {

        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.GeckoToolbar, defStyleAttr, 0);
        mHomeEnabled = array.getBoolean(R.styleable.GeckoToolbar_enableHome, false);
        array.recycle();

        mClearButtonEnabled = true;

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(R.layout.browser_address_bar, this, true);

        mReloadButton = v.findViewById(R.id.reload_button);
        mEditText = v.findViewById(R.id.edit_text);
        mGeckoProgressBar = v.findViewById(R.id.progress_bar);
        mBackground = v.findViewById(R.id.address_bar_background);
        mClearButton = v.findViewById(R.id.clear_button);
        mAddressBarButton = v.findViewById(R.id.security_button);
        mSearchDownButton = v.findViewById(R.id.search_down);
        mSearchUpButton = v.findViewById(R.id.search_up);
        mSearchText = v.findViewById(R.id.search_text);

        mSearchTextDefaultColor = mSearchText.getCurrentTextColor();

        mEditText.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI);
        mEditText.setSingleLine(true);
        mEditText.setSelectAllOnFocus(true);
        mEditText.setHint(R.string.browser_enter_url);

        mClearButton.setEnabled(true);

        // Key listeners
        mEditText.setOnKeyListener(this);
        mSearchDownButton.setOnKeyListener(this);
        mSearchUpButton.setOnKeyListener(this);
        mClearButton.setOnKeyListener(this);

        // Click listeners
        mAddressBarButton.setOnClickListener(this);
        mSearchUpButton.setOnClickListener(this);
        mSearchDownButton.setOnClickListener(this);
        mClearButton.setOnClickListener(this);
        mReloadButton.setOnClickListener(this);


        mAnimColorFrom = ContextCompat.getColor(context, R.color.md_theme_surfaceContainerHigh);
        mAnimColorTo = ContextCompat.getColor(context, R.color.md_theme_surfaceContainerHighest);

        startAnimation(false);

        if (mHomeEnabled) {
            mAddressBarButton.setIconTintResource(R.color.md_theme_onSurface);
            mAddressBarButton.setIconResource(R.drawable.manage_search_24);
            mGeckoProgressBar.setVisibility(View.GONE);
            mReloadButton.setVisibility(View.GONE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search mode
    // ─────────────────────────────────────────────────────────────────────────

    public void enableSearch() {
        mSearchMode = true;

        // Reset edit text for search input
        mEditText.setText("", false);
        mEditText.enableSearchMode(true);
        mEditText.setHint(R.string.browser_find_in_page);

        // Update button states
        mClearButton.setEnabled(true);
        mReloadButton.setVisibility(View.GONE);

        // Show search navigation
        mSearchDownButton.setVisibility(View.VISIBLE);
        mSearchUpButton.setVisibility(View.VISIBLE);

        updateSearchView(true);
        updateViewVisibility(true);

        // Request focus and keyboard AFTER all visibility/layout changes are
        // complete, so the posted showSoftInput runs on a stable layout.
        mEditText.focusAndShowKeyboard();
    }

    private void disableSearch() {
        mSearchMode = false;

        // Restore edit text to URL mode
        mEditText.enableSearchMode(false);
        mEditText.clearFocus();
        mEditText.reset();
        mEditText.setHint(R.string.browser_enter_url);

        // Restore button states
        mClearButton.setEnabled(mClearButtonEnabled);
        mReloadButton.setVisibility(View.VISIBLE);

        // Hide search navigation
        mSearchDownButton.setVisibility(View.GONE);
        mSearchUpButton.setVisibility(View.GONE);

        updateSearchView(false);
        updateViewVisibility(false);
        setSearchText("0/0");
    }

    public void setSearchText(String string) {
        mSearchText.setTextColor(mSearchTextDefaultColor);
        mSearchText.setText(string);
    }

    public void setSearchErrorText(String string) {
        mSearchText.setTextColor(ResourcesCompat.getColor(getResources(), R.color.md_theme_error, null));
        mSearchText.setText(string);
    }

    public void updateSearchView(boolean value) {
        mSearchText.setVisibility(value && mSearchMode ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Address bar text / URI
    // ─────────────────────────────────────────────────────────────────────────

    public String getText() {
        Editable editable = mEditText.getText();
        return editable != null ? editable.toString() : "";
    }

    public void setTextColor(int resColor) {
        int color = ResourcesCompat.getColor(getResources(), resColor, null);
        mEditText.setTextColor(color);
        mEditText.setHintTextColor(color);
    }

    public void setUri(String uri) {
        mEditText.setText(uri);
    }

    public void setUri(String uri, boolean autoComplete) {
        mEditText.setText(uri, autoComplete);
    }

    public void onLocationChange(String uri) {
        if (mSearchMode) disableSearch();
        mEditText.setLocation(uri);
    }

    public void clearText() {
        if (mSearchMode) {
            clearFocus();
            return;
        }
        if (mEditText != null) {
            mEditText.setText("", false);
        }
    }

    public AutoCompleteEditText getAutoCompleteEditText() {
        return mEditText;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Security / Shield icon
    // ─────────────────────────────────────────────────────────────────────────

    public void setSecure(boolean value) {
        mSecureEnabled = value;
        updateShieldIcon();
    }

    public void setTrackingEnabled(boolean value) {
        mTrackingEnabled = value;
        updateShieldIcon();
    }

    public void setAdsEnabled(boolean value) {
        mAdsEnabled = value;
        updateShieldIcon();
    }

    /**
     * Resolves the shield icon from the protection state. The site is
     * "fully protected" only when both ETP tracking and uBlock ad
     * filtering are active for it; otherwise the icon flips to the
     * privacy-tip variant. This collapses the previous matrix-plus-dot
     * (4 shield variants + a red badge for ads-off) into a single
     * binary glance: full shield = full protection, privacy-tip = a
     * protection layer is off. The secure × insecure axis is preserved.
     *
     *                       full protection         reduced
     * secure   true         ic_shield_24            ic_shield_privacy_tip_24
     * secure   false        ic_shield_bad_24        ic_shield_privacy_tip_bad_24
     */
    private void updateShieldIcon() {
        final boolean fullProtection = mTrackingEnabled && mAdsEnabled;
        final int icon;
        if (fullProtection) {
            icon = mSecureEnabled ? R.drawable.ic_shield_24 : R.drawable.ic_shield_bad_24;
        } else {
            icon = mSecureEnabled ? R.drawable.ic_shield_privacy_tip_24 : R.drawable.ic_shield_privacy_tip_bad_24;
        }
        if (icon == mCurrentShieldRes) { return; }

        if (mCurrentShieldRes == 0) {
            // First bind — no animation, just paint the icon.
            mAddressBarButton.setIconResource(icon);
        } else {
            // Brief fade + scale pulse: shrinks to 85% while the icon
            // swaps, then settles back. Reads as 'this changed' without
            // being loud enough to draw the eye away from the page.
            mAddressBarButton.animate()
                    .alpha(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(80L)
                    .withEndAction(() -> {
                        mAddressBarButton.setIconResource(icon);
                        mAddressBarButton.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(160L)
                                .start();
                    })
                    .start();
        }
        mCurrentShieldRes = icon;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress / Loading
    // ─────────────────────────────────────────────────────────────────────────

    public void setLoading(boolean value) {
        if (value) {
            mReloadButton.setId(R.id.stop_button);
            mReloadButton.setIconResource(R.drawable.ic_baseline_close_24);
        } else {
            mReloadButton.setId(R.id.reload_button);
            mReloadButton.setIconResource(R.drawable.ic_refresh_24);
        }
    }

    public void setProgress(int progress) {
        mGeckoProgressBar.setProgress(progress);
        mGeckoProgressBar.setVisibility(progress > 0 && progress < 100 ? View.VISIBLE : View.GONE);
    }

    public void setAutoCompleteVisible(boolean visible) {
        mGeckoProgressBar.setAutoCompleteVisible(visible);
    }

    public boolean isAutoCompleteVisible() {
        return mGeckoProgressBar.isAutoCompleteVisible();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Visibility & Animation
    // ─────────────────────────────────────────────────────────────────────────

    public void show() {
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        setVisibility(View.GONE);
    }

    public void startAnimation(boolean hasFocus) {
        GradientDrawable gradientDrawable = (GradientDrawable) mBackground.getBackground();
        if (hasFocus) {
            ValueAnimator colorAnimation = ValueAnimator.ofObject(
                    new ArgbEvaluator(), mAnimColorFrom, mAnimColorTo);
            ValueAnimator radiusAnimator = ValueAnimator.ofFloat(
                    getResources().getDimensionPixelSize(R.dimen.address_bar_radius),
                    getResources().getDimensionPixelSize(R.dimen.address_bar_radius_focused));
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(radiusAnimator, colorAnimation);
            colorAnimation.addUpdateListener(va ->
                    gradientDrawable.setColor((int) va.getAnimatedValue()));
            radiusAnimator.addUpdateListener(va ->
                    gradientDrawable.setCornerRadius((float) va.getAnimatedValue()));
            animatorSet.setDuration(DURATION);
            animatorSet.start();
        } else {
            gradientDrawable.setColor(mAnimColorFrom);
            gradientDrawable.setCornerRadius(
                    getResources().getDimensionPixelSize(R.dimen.address_bar_radius));
        }
    }

    public void updateTheme(Activity activity, boolean incognito) {
        updateTheme(activity, incognito, true);
    }

    /**
     * @param tonalHolder picks the toolbar's holder tone per surface.
     * TRUE (browser, the 2-arg default): surfaceContainer — the full
     * tonal frame. The browser cannot split this: its inset-padded root
     * leaves the status strip to the decor (painted surfaceContainer by
     * BrowserFragment), so the toolbar must match or the top seams.
     * FALSE (Home / incognito Home): plain surface — the toolbar merges
     * with the page canvas. The toolbar pads itself by the status inset
     * there (HomeFragment's insets listener), so this tone also paints
     * the status strip: a seamless quiet top over the dashboard, no
     * tonal band floating on the canvas. Bottom chrome stays tonal on
     * both. The URL pill keeps a TWO-step lift in either mode:
     * surfaceContainerHigh on the quiet holder, surfaceContainerHighest
     * on the tonal one (see the pill block below).
     */
    public void updateTheme(Activity activity, boolean incognito, boolean tonalHolder) {

        int surfaceColor = tonalHolder
                ? IncognitoColors.getSurfaceContainer(activity, incognito)
                : IncognitoColors.getSurface(activity, incognito);
        int onSurfaceColor = IncognitoColors.getOnSurface(activity, incognito);
        int onSurfaceVariant = IncognitoColors.getOnSurfaceVariant(activity, incognito);
        int surfaceContainerHigh = IncognitoColors.getSurfaceContainerHigh(activity, incognito);

        // 1. Toolbar background. Painted on THIS view as well as the
        // address_bar_holder child: when the toolbar self-pads by the
        // status inset (the edge-to-edge browser, and Home), the inset
        // strip lies in THIS view's padding area, which a child's
        // background can never reach — painting only the holder would
        // leave the status strip transparent over whatever is behind.
        setBackgroundColor(surfaceColor);
        View addressBarHolder = findViewById(R.id.address_bar_holder);
        if (addressBarHolder != null) {
            addressBarHolder.setBackgroundColor(surfaceColor);
        }

        // 2. Address bar rounded background (the GradientDrawable pill).
        // PER-HOLDER fill — the token-level contrast fix (a hairline
        // outline was tried and reverted as a band-aid):
        //  - quiet holder (Home): surfaceContainerHigh — the M3 SEARCH-BAR
        //    role (Material 3 docked search = surfaceContainerHigh). The
        //    Home cards are the FILLED-CARD role one step up
        //    (surfaceContainerHighest), so the bar reads one step below the
        //    cards — the by-the-book search/card relationship, NOT a match.
        //  - tonal holder (browser): surfaceContainerHighest — the High
        //    fill sat only ONE step above the surfaceContainer holder and
        //    read nearly flat; Highest restores a two-step lift.
        // Trade-off, accepted: the FOCUS pulse (mAnimColorFrom → Highest)
        // becomes a no-op on the tonal holder since rest == Highest there;
        // on the browser, focusing the bar immediately raises the
        // AutoCompleteView panel, which is the real focus affordance.
        int pillRestColor = tonalHolder
                ? IncognitoColors.getSurfaceContainerHighest(activity, incognito)
                : surfaceContainerHigh;
        if (mBackground != null && mBackground.getBackground() instanceof GradientDrawable gd) {
            // MUTATE before setColor: @drawable/address_bar is ONE cached
            // resource, so its ConstantState is shared across every toolbar
            // that inflated it. Without mutate, the Home toolbar (High) and
            // the browser toolbar (Highest) overwrite each other's colour on
            // that shared state — whichever updateTheme ran last wins for
            // BOTH, so after visiting the browser the Home pill came back at
            // Highest, one step lighter than the cards it is meant to match.
            // mutate() gives this toolbar's pill its own state.
            gd.mutate();
            gd.setColor(pillRestColor);
        }

        // 3. Edit text colors
        if (mEditText != null) {
            mEditText.setTextColor(onSurfaceColor);
            mEditText.setHintTextColor(onSurfaceVariant);
            // Text selection highlight
            int highlightColor = incognito
                    ? IncognitoColors.getPrimary(activity, true)
                    : ContextCompat.getColor(activity, R.color.md_theme_inversePrimary);
            mEditText.setHighlightColor(highlightColor);
            mEditText.setAutoCompleteHighlightColor(highlightColor);
        }

        // 4. Security button icon tint
        if (mAddressBarButton != null) {
            mAddressBarButton.setIconTint(ColorStateList.valueOf(onSurfaceColor));
        }

        // 5. Reload/stop button icon tint
        if (mReloadButton != null) {
            mReloadButton.setIconTint(ColorStateList.valueOf(onSurfaceColor));
        }

        // 6. Clear button icon tint
        if (mClearButton != null) {
            mClearButton.setIconTint(ColorStateList.valueOf(onSurfaceColor));
        }

        // 7. Search up/down button icon tints
        if (mSearchUpButton instanceof MaterialButton btn) {
            btn.setIconTint(ColorStateList.valueOf(onSurfaceColor));
        }
        if (mSearchDownButton instanceof MaterialButton btn) {
            btn.setIconTint(ColorStateList.valueOf(onSurfaceColor));
        }

        // 8. Search text color
        if (mSearchText != null) {
            mSearchText.setTextColor(onSurfaceVariant);
            mSearchTextDefaultColor = onSurfaceVariant;
        }

        // 9. Update the animation colors so focus/unfocus uses the right palette.
        // surfaceBright collapses to the same value as surface in the light M3
        // palette (#FBF9FB), which made the focused pill disappear against the
        // address-bar holder. surfaceContainerHighest is one M3 step above the
        // resting surfaceContainerHigh tone in both themes, so focus stays
        // visible without breaking the dark-theme appearance.
        // From = the holder-dependent REST fill, so the unfocus animation
        // returns the pill to its true resting tone (animating back to a
        // hardcoded High would visibly darken the browser's Highest pill).
        mAnimColorFrom = pillRestColor;
        mAnimColorTo = IncognitoColors.getSurfaceContainerHighest(activity, incognito);
    }

    public void updateViewVisibility(final boolean hasFocus) {
        final boolean hasText = !TextUtils.isEmpty(mEditText.getText());

        mAddressBarButton.setVisibility(hasFocus ? GONE : VISIBLE);
        mClearButton.setVisibility(hasFocus && hasText ? VISIBLE : GONE);

        if (mHomeEnabled) {
            mReloadButton.setVisibility(GONE);
        } else {
            mReloadButton.setVisibility(hasFocus ? GONE : VISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scrolling behavior
    // ─────────────────────────────────────────────────────────────────────────

    public void enableScrolling() {
        GeckoToolbarBehavior behavior = getToolbarBehavior();
        if (behavior != null) behavior.enableScrolling();
    }

    public void disableScrolling() {
        GeckoToolbarBehavior behavior = getToolbarBehavior();
        if (behavior != null) behavior.disableScrolling();
    }

    /** Animates the toolbar fully on-screen (the bottom bar follows via its slaved behavior). */
    public void forceExpand() {
        GeckoToolbarBehavior behavior = getToolbarBehavior();
        if (behavior != null) behavior.forceExpand(this);
    }

    /** Animates the toolbar fully off-screen (the bottom bar follows via its slaved behavior). */
    public void forceCollapse() {
        GeckoToolbarBehavior behavior = getToolbarBehavior();
        if (behavior != null) behavior.forceCollapse(this);
    }

    /**
     * Instanceof-guarded on both the LayoutParams and the Behavior: in
     * HomeFragment's layout this toolbar sits inside an AppBarLayout
     * (AppBarLayout.LayoutParams, no behavior), so the old blind casts were
     * one stray call away from a ClassCastException.
     */
    @Nullable
    private GeckoToolbarBehavior getToolbarBehavior() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (!(lp instanceof CoordinatorLayout.LayoutParams)) {
            return null;
        }
        CoordinatorLayout.Behavior<?> behavior = ((CoordinatorLayout.LayoutParams) lp).getBehavior();
        if (behavior instanceof GeckoToolbarBehavior) {
            return (GeckoToolbarBehavior) behavior;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Focus
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void clearFocus() {
        super.clearFocus();
        if (mSearchMode) {
            disableSearch();
            if (mOnClearFocusListener != null) {
                mOnClearFocusListener.onToolbarClearFocus();
            }
        }
        if (mEditText != null) {
            mEditText.clearFocus();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    public void setListener(OnToolbarListener listener) {
        this.mOnToolbarListener = listener;
    }

    public void setOnClearFocusListener(OnClearFocusListener listener) {
        this.mOnClearFocusListener = listener;
    }
}