package com.solarized.firedown.phone.dialogs;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.applinks.AppLinkTarget;
import com.solarized.firedown.utils.FragmentArgs;

/**
 * Asks before a link leaves Firedown for an app: the app's icon and name,
 * the site the link came from, Stay in Firedown or Open. Shown for an app
 * link the user tapped or typed (AppLinkPolicy.Outcome.PROMPT).
 *
 * <p>Open starts the app from here. Stay is BrowserFragment's: it loads the
 * link's web version in the tab and quiets page-fired links to this app in
 * the tab for an hour, so it is reported as a fragment result. Back, a swipe
 * or a tap outside does nothing.</p>
 */
public class OpenInAppSheetDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String TAG = "OpenInAppSheet";

    public static final String RESULT_KEY = "com.solarized.firedown.openinapp.result";
    /** True: the user chose Stay. False: the app failed to start. */
    public static final String RESULT_STAY = "com.solarized.firedown.openinapp.stay";
    public static final String RESULT_WEB_URL = "com.solarized.firedown.openinapp.web";
    public static final String RESULT_APP_KEY = "com.solarized.firedown.openinapp.key";

    private static final String ARG_APP_NAME = "com.solarized.firedown.openinapp.name";
    private static final String ARG_PACKAGE = "com.solarized.firedown.openinapp.package";
    private static final String ARG_SOURCE_HOST = "com.solarized.firedown.openinapp.source";

    @Nullable
    private Intent mAppIntent;

    @NonNull
    public static Bundle args(@NonNull AppLinkTarget target, @Nullable String sourceHost, boolean incognito) {
        Bundle args = new Bundle();
        args.putParcelable(Keys.ITEM_ID, target.appIntent);
        args.putString(ARG_APP_NAME, target.appName);
        args.putString(ARG_PACKAGE, target.packageName);
        args.putString(ARG_SOURCE_HOST, sourceHost);
        args.putString(RESULT_WEB_URL, target.webUrl);
        args.putString(RESULT_APP_KEY, target.key);
        args.putBoolean(Keys.IS_INCOGNITO, incognito);
        return args;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_open_in_app, container, false);
        Bundle args = requireArguments();
        mAppIntent = FragmentArgs.parcelable(this, Keys.ITEM_ID, Intent.class);
        String appName = args.getString(ARG_APP_NAME);
        String packageName = args.getString(ARG_PACKAGE);
        String sourceHost = args.getString(ARG_SOURCE_HOST);

        bindIcon(mView.findViewById(R.id.open_in_app_icon), packageName);

        TextView title = mView.findViewById(R.id.open_in_app_title);
        title.setText(appName != null
                ? getString(R.string.open_in_app_title_named, appName)
                : getString(R.string.open_in_app_title_any));

        TextView source = mView.findViewById(R.id.open_in_app_source);
        if (sourceHost != null) {
            source.setText(getString(R.string.open_in_app_link_from, sourceHost));
        } else {
            source.setVisibility(View.GONE);
        }

        mView.findViewById(R.id.open_in_app_stay).setOnClickListener(v -> {
            report(true);
            dismiss();
        });
        mView.findViewById(R.id.open_in_app_open).setOnClickListener(v -> {
            boolean started = startApp();
            if (!started) {
                report(false);
            }
            dismiss();
        });
        return mView;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Restored without its intent (should not happen): nothing to ask.
        if (mAppIntent == null) {
            dismissAllowingStateLoss();
        }
    }

    private void bindIcon(@NonNull ImageView icon, @Nullable String packageName) {
        Drawable appIcon = null;
        if (packageName != null) {
            try {
                appIcon = requireContext().getPackageManager().getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                appIcon = null;
            }
        }
        if (appIcon != null) {
            icon.setImageDrawable(appIcon);
            return;
        }
        // Several apps can open it (Android will ask which): a neutral glyph.
        icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_open_in_new_24));
        int ink = MaterialColors.getColor(icon, com.google.android.material.R.attr.colorOnSurfaceVariant);
        icon.setImageTintList(ColorStateList.valueOf(ink));
        icon.setScaleType(ImageView.ScaleType.CENTER);
    }

    private boolean startApp() {
        if (mActivity == null || mAppIntent == null) {
            return false;
        }
        try {
            mActivity.startActivity(mAppIntent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "app did not start", e);
            }
            return false;
        }
    }

    private void report(boolean stay) {
        Bundle args = requireArguments();
        Bundle result = new Bundle();
        result.putBoolean(RESULT_STAY, stay);
        result.putString(RESULT_WEB_URL, args.getString(RESULT_WEB_URL));
        result.putString(RESULT_APP_KEY, args.getString(RESULT_APP_KEY));
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
    }
}
