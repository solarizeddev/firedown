package com.solarized.firedown.geckoview;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.badge.ExperimentalBadgeUtils;
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

    private View mBadgeButton;

    private View mSearchDownButton;

    private TextView mSearchText;

    private AutoCompleteEditText mEditText;

    private MaterialButton mClearButton;

    private MaterialButton mAddressBarButton;

    private MaterialButton mReloadButton;

    private AppCompatImageView mBackground;

    private boolean mHomeEnabled;

    private boolean mTrackingEnabled;

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

    @OptIn(markerClass = ExperimentalBadgeUtils.class)
    private void init(Context context, AttributeSet attrs, int defStyleAttr) {

        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.GeckoToolbar, defStyleAttr, 0);
        mHomeEnabled = array.getBoolean(R.styleable.GeckoToolbar_enableHome, false);
        array.recycle();

        mClearButtonEnabled = true;

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(R.layout.browser_address_bar, this, true);

        mBadgeButton = v.findViewById(R.id.security_badge);
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

    public void disableSearch() {
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
        if (GeckoResources.isOnboarding(uri)){
            mEditText.setText(GeckoResources.ABOUT_ONBOARDING);
            return;
        }
        mEditText.setText(uri);
    }

    public void setUri(String uri, boolean autoComplete) {
        if (GeckoResources.isOnboarding(uri)){
            mEditText.setText(GeckoResources.ABOUT_ONBOARDING, false);
            return;
        }
        mEditText.setText(uri, autoComplete);
    }

    public void resetLocation() {
        mEditText.resetLocation();
        mEditText.setText("", false);
    }

    public void onLocationChange(String uri) {
        if (GeckoResources.isOnboarding(uri)){
            mEditText.setLocation(GeckoResources.ABOUT_ONBOARDING);
            return;
        }
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
        mBadgeButton.setVisibility(value ? INVISIBLE : VISIBLE);
    }

    /**
     * Resolves the shield icon based on the current (secure × tracking) state.
     *
     *                  tracking ON              tracking OFF
     * secure   true    ic_shield_24             ic_shield_privacy_tip_24
     * secure   false   ic_shield_bad_24         ic_shield_privacy_tip_bad_24
     */
    private void updateShieldIcon() {
        final int icon;
        if (mTrackingEnabled) {
            icon = mSecureEnabled ? R.drawable.ic_shield_24 : R.drawable.ic_shield_bad_24;
        } else {
            icon = mSecureEnabled ? R.drawable.ic_shield_privacy_tip_24 : R.drawable.ic_shield_privacy_tip_bad_24;
        }
        mAddressBarButton.setIconResource(icon);
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

    public int getProgress() {
        return mGeckoProgressBar.getProgress();
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

        int surfaceColor = IncognitoColors.getSurface(activity, incognito);
        int onSurfaceColor = IncognitoColors.getOnSurface(activity, incognito);
        int onSurfaceVariant = IncognitoColors.getOnSurfaceVariant(activity, incognito);
        int surfaceContainerHigh = IncognitoColors.getSurfaceContainerHigh(activity, incognito);

        // 1. Address bar holder background (the ConstraintLayout root)
        View addressBarHolder = findViewById(R.id.address_bar_holder);
        if (addressBarHolder != null) {
            addressBarHolder.setBackgroundColor(surfaceColor);
        }

        // 2. Address bar rounded background (the GradientDrawable pill)
        if (mBackground != null && mBackground.getBackground() instanceof GradientDrawable gd) {
            gd.setColor(surfaceContainerHigh);
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
            mAddressBarButton.setIconTint(android.content.res.ColorStateList.valueOf(onSurfaceColor));
        }

        // 5. Reload/stop button icon tint
        if (mReloadButton != null) {
            mReloadButton.setIconTint(android.content.res.ColorStateList.valueOf(onSurfaceColor));
        }

        // 6. Clear button icon tint
        if (mClearButton != null) {
            mClearButton.setIconTint(android.content.res.ColorStateList.valueOf(onSurfaceColor));
        }

        // 7. Search up/down button icon tints
        if (mSearchUpButton instanceof MaterialButton btn) {
            btn.setIconTint(android.content.res.ColorStateList.valueOf(onSurfaceColor));
        }
        if (mSearchDownButton instanceof MaterialButton btn) {
            btn.setIconTint(android.content.res.ColorStateList.valueOf(onSurfaceColor));
        }

        // 8. Search text color
        if (mSearchText != null) {
            mSearchText.setTextColor(onSurfaceVariant);
            mSearchTextDefaultColor = onSurfaceVariant;
        }

        // 9. Update the animation colors so focus/unfocus uses the right palette
        mAnimColorFrom = surfaceContainerHigh;
        mAnimColorTo = IncognitoColors.getSurfaceBright(activity, incognito);
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

    @Nullable
    private GeckoToolbarBehavior getToolbarBehavior() {
        CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) getLayoutParams();
        return lp != null ? (GeckoToolbarBehavior) lp.getBehavior() : null;
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