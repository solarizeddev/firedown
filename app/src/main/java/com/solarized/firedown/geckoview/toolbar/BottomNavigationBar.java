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

        boolean enableCradle = array.getBoolean(R.styleable.BottomNavigationBar_enableCradle, false);

        array.recycle();

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.bottom_bar, this, true);

        ViewGroup viewGroup = (ViewGroup) ((ViewGroup)v).getChildAt(0);

        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            final View child = viewGroup.getChildAt(i);
            child.setOnClickListener(this);
        }

        View newTabButton = v.findViewById(R.id.new_tab_button);
        View downloadButton = v.findViewById(R.id.downloads_button);
        View searchIcon = v.findViewById(R.id.search_button);
        View spacer = v.findViewById(R.id.spacer);
        spacer.setVisibility(enableCradle ? View.VISIBLE : View.GONE);
        searchIcon.setVisibility(enableCradle ? View.GONE : View.VISIBLE);
        searchIcon.setOnClickListener(this);
        newTabButton.setOnLongClickListener(this);

        mTabsCountButton = v.findViewById(R.id.tab_button);
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

        applyWindowInsets();

    }


    public void updateTheme(Activity activity, boolean incognito) {
        Context context = getContext();

        int surfaceColor = IncognitoColors.getSurface(activity, incognito);
        int iconColor = IncognitoColors.getOnSurface(activity, incognito);

        ColorStateList iconTint = ColorStateList.valueOf(iconColor);

        // Bar background — set on the LinearLayout child
        ViewGroup bar = (ViewGroup) getChildAt(0);
        if (bar != null) {
            bar.setBackgroundColor(surfaceColor);
        }

        // Tint each icon button
        View newTabBtn = findViewById(R.id.new_tab_button);
        if (newTabBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) newTabBtn, iconTint);
        }

        View searchBtn = findViewById(R.id.search_button);
        if (searchBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) searchBtn, iconTint);
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

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures() |
                    WindowInsetsCompat.Type.displayCutout());
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, insets.bottom);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
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
