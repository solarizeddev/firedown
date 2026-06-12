package com.solarized.firedown.geckoview.toolbar;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
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
        // Browser AND both Homes set this true so an externally-anchored hero
        // FAB (capture on Browser, Bookmarks on Home) can sit over the middle
        // slot without colliding with a flat action icon underneath. No current
        // layout leaves it false; the attribute stays for any future surface
        // that wants the in-slot action instead of a FAB.
        boolean hideMiddleSlot = array.getBoolean(R.styleable.BottomNavigationBar_hideMiddleSlot, false);
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
        mBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.md_theme_primaryContainer));
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

        // FRAMED model: the bar does NOT self-pad the nav inset — the
        // browser root reserves the nav strip with its own safe-area
        // padding, so the bar is a plain app_bar_size-tall bar. (It no
        // longer consumes insets either; the root consumes them.)
    }


    public void updateTheme(Activity activity, boolean incognito) {
        Context context = getContext();

        // surfaceContainer, NOT surface — the same tonal tone the tabs
        // screen's action bar uses (TabsHolderFragment), so the bar reads
        // as a bar instead of dissolving into the dark window background,
        // and the docked hero FAB visibly seats INTO it on every screen.
        int surfaceColor = IncognitoColors.getSurfaceContainer(activity, incognito);
        int iconColor = IncognitoColors.getOnSurface(activity, incognito);

        ColorStateList iconTint = ColorStateList.valueOf(iconColor);

        // Background on THIS view (the FrameLayout), not the LinearLayout
        // child — paints the whole bar in one tone. (In the framed model
        // the bar no longer self-pads the nav inset; the root reserves the
        // nav strip, so there is no inset strip to leak here.)
        setBackgroundColor(surfaceColor);

        // Tint each icon button
        View newTabBtn = findViewById(R.id.new_tab_button);
        if (newTabBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) newTabBtn, iconTint);
        }

        // The cradle slot is Bookmarks in both modes — the list is
        // just URLs the user explicitly saved, so it doesn't leak
        // any incognito-session browsing state. The id stays
        // 'search_button' since it's the slot id, not the action.
        // Every current layout hides this slot under a hero FAB
        // (hideMiddleSlot), so the glyph/tint below is dormant —
        // kept for any future layout that shows the slot.
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

        // TabsBrowserButton (custom view — uses setColorFilter or a tint method)
        if (mTabsCountButton != null) {
            mTabsCountButton.setTabsTextColor(iconColor);
            Drawable bg = mTabsCountButton.getTabsBackground();
            if (bg instanceof GradientDrawable gd) {
                // Mutate so we don't affect the shared drawable cache
                gd.mutate();
                gd.setStroke(
                        (int) (1.8f * getResources().getDisplayMetrics().density),
                        iconColor);
            }
        }

        // Badge color
        if (mBadge != null) {
            mBadge.setBackgroundColor(IncognitoColors.getPrimaryContainer(context, incognito));
        }
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
