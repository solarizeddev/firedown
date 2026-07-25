package com.solarized.firedown.geckoview.toolbar;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.badge.ExperimentalBadgeUtils;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.browser.TabsBrowserButton;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.badge.BadgeUtils;


public class BottomNavigationBar extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = BottomNavigationBar.class.getName();

    private OnBottomBarListener mOnBottomBarListener;

    private TabsBrowserButton mTabsCountButton;

    private BadgeDrawable mBadge;

    // Top hairline divider (Firefox-parity). The bar is painted the page
    // SURFACE tone (not a tonal surfaceContainer step), so it would dissolve
    // into surface content (Home) or arbitrary web content (Browser) without
    // this 1dp line. Drawn as a top-gravity child OVER the bar background, so
    // it rides the bar's scroll-hide. Coloured (incognito-aware) in updateTheme.
    private View mSeparator;


    public interface OnBottomBarListener {
        void onBottomBarButtonClick(View v, int id);
        boolean onBottomBarButtonLongClick(View v, int id);

    }

    public BottomNavigationBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
        init(context, attrs, 0);
    }


    public BottomNavigationBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }


    @Override
    public void onClick(View v) {
        if(mOnBottomBarListener != null) mOnBottomBarListener.onBottomBarButtonClick(v, v.getId());
    }

    @Override
    public boolean onLongClick(View v) {
        if(mOnBottomBarListener != null) {
            return mOnBottomBarListener.onBottomBarButtonLongClick(v, v.getId());
        }
        return false;
    }


    private void init(Context context, AttributeSet attrs, int defStyleAttr) {

        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.BottomNavigationBar, defStyleAttr, 0);
        // The Browser sets this true so its externally-anchored capture hero
        // FAB can sit over the middle slot without colliding with a flat action
        // icon underneath. Both Homes leave it false (default): they show the
        // flat in-slot Bookmarks button instead of a FAB.
        boolean hideMiddleSlot = array.getBoolean(R.styleable.BottomNavigationBar_hideMiddleSlot, false);
        // Default TRUE: Home/incognito Home self-pad the nav inset so the bar
        // owns the nav strip. The browser sets it FALSE — its framed root
        // reserves the strip with all-sides padding, so a self-pad there
        // would double-inset (see the styleable doc).
        boolean mSelfPadSystemBars = array.getBoolean(R.styleable.BottomNavigationBar_selfPadSystemBars, true);
        array.recycle();

        LayoutInflater.from(context).inflate(R.layout.bottom_bar, this, true);
        // Inflate-with-attach returns `this`; the LinearLayout is its sole child.
        ViewGroup bar = (ViewGroup) getChildAt(0);
        for (int i = 0; i < bar.getChildCount(); i++) {
            bar.getChildAt(i).setOnClickListener(this);
        }

        View newTabButton = findViewById(R.id.new_tab_button);
        View downloadButton = findViewById(R.id.downloads_button);
        View searchIcon = findViewById(R.id.search_button);
        // INVISIBLE (not GONE) so the LinearLayout still weighs the slot —
        // keeps the four visible cells evenly distributed across the bar.
        searchIcon.setVisibility(hideMiddleSlot ? View.INVISIBLE : View.VISIBLE);
        newTabButton.setOnLongClickListener(this);
        downloadButton.setOnLongClickListener(this);

        mTabsCountButton = findViewById(R.id.tab_button);
        mBadge = BadgeDrawable.create(context);
        mBadge.setVisible(false);
        // The badge is a bare dot with no label — its background IS the signal,
        // so it is ink, not a container. colorPrimary keeps it legible on the
        // bottom bar in both themes; colorPrimaryContainer is now a proper
        // container tone (pale in light, dark in dark) and would disappear.
        mBadge.setBackgroundColor(IncognitoColors.getPrimary(context, false));
        mBadge.setVerticalOffset(getResources().getDimensionPixelOffset(R.dimen.badge_vertical_offset));
        mBadge.setHorizontalOffset(getResources().getDimensionPixelOffset(R.dimen.badge_horizontal_offset));

        downloadButton.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @OptIn(markerClass = ExperimentalBadgeUtils.class)
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                downloadButton.removeOnLayoutChangeListener(this);
                BadgeUtils.attachBadgeDrawable(mBadge, downloadButton);
            }
        });

        // Top hairline divider, added as the LAST child of this FrameLayout so
        // it draws OVER the bar background at the very top edge. Not a child of
        // the inflated button row, so the click-listener loop above never sees
        // it. 1dp tall; tinted in updateTheme.
        int hairline = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        mSeparator = new View(context);
        mSeparator.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, hairline, Gravity.TOP));
        addView(mSeparator);

        // Self-pad the nav inset UNLESS the host opted out (the browser's
        // framed root reserves the strip instead — see selfPadSystemBars).
        if (mSelfPadSystemBars) {
            applyWindowInsets();
        }
    }


    public void updateTheme(Activity activity, boolean incognito) {
        Context context = getContext();

        // SURFACE (Firefox-parity) — the bar is the page background tone, not a
        // tonal surfaceContainer step. The old tonal tone existed so the bar
        // "read as a bar" and the docked hero FAB seated into it; the FAB is
        // gone, so the bar now rides flat on the background and the top hairline
        // (mSeparator, below) provides the division instead of a colour step.
        int surfaceColor = IncognitoColors.getSurface(activity, incognito);
        int iconColor = IncognitoColors.getOnSurface(activity, incognito);

        ColorStateList iconTint = ColorStateList.valueOf(iconColor);

        // Background on THIS view (the FrameLayout), not the LinearLayout
        // child — paints the whole bar in one tone INCLUDING the nav-inset
        // self-pad strip when present (Home), so the gesture area matches
        // the bar; a child-only background would leave that strip a
        // two-tone seam. (The browser opts out of the self-pad, so there's
        // no strip there, but painting the outer view is correct either way.)
        setBackgroundColor(surfaceColor);

        // Tint each icon button
        View newTabBtn = findViewById(R.id.new_tab_button);
        if (newTabBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) newTabBtn, iconTint);
        }

        // The middle slot is Bookmarks in both Home modes — the list is
        // just URLs the user explicitly saved, so it doesn't leak
        // any incognito-session browsing state. The id stays
        // 'search_button' since it's the slot id, not the action.
        // The Browser hides this slot under its capture hero FAB
        // (hideMiddleSlot), so the glyph/tint below is dormant there.
        AppCompatImageButton searchBtn = findViewById(R.id.search_button);
        if (searchBtn != null) {
            searchBtn.setImageResource(R.drawable.ic_bookmark_border_24);
            searchBtn.setContentDescription(getContext().getString(R.string.library_bookmarks));
            ImageViewCompat.setImageTintList(searchBtn, iconTint);
        }

        AppCompatImageButton downloadsBtn = findViewById(R.id.downloads_button);
        if (downloadsBtn != null) {
            downloadsBtn.setImageResource(incognito ? R.drawable.ic_lock_24 : R.drawable.download_24);
            ImageViewCompat.setImageTintList(downloadsBtn, iconTint);
        }

        View moreBtn = findViewById(R.id.more_button);
        if (moreBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) moreBtn, iconTint);
        }

        // Tab counter (TabCountDrawable) — one tint colours its rect + digits
        // (it owns its own stroke, so no GradientDrawable restroke here).
        if (mTabsCountButton != null) {
            mTabsCountButton.setTabsTextColor(iconColor);
        }

        // Badge color — the accent, not the container (see the create() call).
        if (mBadge != null) {
            mBadge.setBackgroundColor(IncognitoColors.getPrimary(context, incognito));
        }

        // Top hairline — a soft line (outlineVariant read too prominent).
        // Any dark surface (system-dark OR incognito) gets a translucent-WHITE
        // line (a black groove is invisible on dark); a light surface gets a
        // faint translucent-BLACK line.
        if (mSeparator != null) {
            int nightMode = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = incognito || nightMode == Configuration.UI_MODE_NIGHT_YES;
            mSeparator.setBackgroundColor(ContextCompat.getColor(context,
                    dark ? R.color.bottom_bar_divider_dark : R.color.bottom_bar_divider_light));
        }
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            // Self-pad the bottom (nav) inset so the bar's background owns
            // the nav strip; l/r for cutouts. Consume so the inset doesn't
            // also reach a descendant. (Only registered when the host did
            // NOT opt out via selfPadSystemBars — i.e. Home, not browser.)
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    public void onBadgeCount(int count){
        mBadge.setVisible(count > 0);
    }

    public void onTabsCount(int count) {
        if(mTabsCountButton != null) mTabsCountButton.setTabsCount(count);
    }

    public void setListener(OnBottomBarListener listener) {
        this.mOnBottomBarListener = listener;
    }

    public void show(){
        setVisibility(View.VISIBLE);
    }

    public void hide(){
        setVisibility(View.GONE);
    }




}
