package com.solarized.firedown.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.AndroidResources;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;

import javax.inject.Inject;

public class BasePreferenceFragment extends PreferenceFragmentCompat {

    private static final String TAG = BasePreferenceFragment.class.getName();

    protected NavController mNavController;

    protected SharedPreferences mSharedPreferences;

    protected BaseActivity mActivity;
    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Let the list scroll content under the nav bar
        RecyclerView listView = getListView();
        if (listView != null) {
            listView.setClipToPadding(false);
        }

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() |
                            WindowInsetsCompat.Type.displayCutout());

            // Apply horizontal + bottom insets to the root so everything respects them
            v.setPadding(insets.left, v.getPaddingTop(), insets.right, insets.bottom);

            // Apply bottom inset as padding to the list so scrolled content
            // goes under the nav bar but the last items aren't permanently clipped
            if (listView != null) {
                listView.setPadding(
                        listView.getPaddingLeft(),
                        listView.getPaddingTop(),
                        listView.getPaddingRight(),
                        insets.bottom);
            }

            return WindowInsetsCompat.CONSUMED;
            });
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNavController = getNavController();
    }

    @NonNull
    public NavController getNavController() {
        Fragment fragment = mActivity.getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (!(fragment instanceof NavHostFragment)) {
            throw new IllegalStateException("Activity " + this
                    + " does not have a NavHostFragment");
        }
        return ((NavHostFragment) fragment).getNavController();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if(context instanceof BaseActivity)
            mActivity = (BaseActivity) context;

    }

    @Override
    public void onDetach(){
        super.onDetach();
        mActivity = null;
    }

    protected void tintIcons() {

        int colorAttr = android.R.attr.textColorSecondary;

        TypedArray ta = mActivity.getTheme().obtainStyledAttributes(new int[]{colorAttr});

        Preference preference = getPreferenceScreen();

        int iconColor = ta.getColor(0, 0);

        if (preference instanceof PreferenceGroup group) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                tintIcons(group.getPreference(i), iconColor);
            }
        }

        ta.recycle();


    }

    private void tintIcons(Preference preference, int iconColor) {
        if (preference instanceof PreferenceGroup group) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                tintIcons(group.getPreference(i), iconColor);
            }
        } else {
            Drawable icon = preference.getIcon();
            if (icon != null) {
                DrawableCompat.setTint(icon, iconColor);
            }
        }
    }
}
