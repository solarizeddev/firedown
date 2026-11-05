package com.solarized.firedown.phone.dialogs;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;

/**
 * "This video needs DRM": shown once per site (DrmNoticeSites) when a page
 * asks for DRM while the switch is off. It only asks; the browser acts on
 * the answer ({@link #RESULT_KEY}), which is also how the security sheet's
 * "DRM is off" row reports Turn on, so turning DRM on has one implementation.
 * Not now, Back, a swipe or a tap outside all answer "not now": the page
 * keeps the dot on the shield, which is the way back.
 */
public class DrmSheetDialogFragment extends BaseBottomSheetDialogFragment {

    public static final String RESULT_KEY = "com.solarized.firedown.drm.result";
    /** True: turn DRM on. False: not now. */
    public static final String RESULT_TURN_ON = "com.solarized.firedown.drm.turn_on";
    /** The site the question was about, compared with the tab's at the answer. */
    public static final String RESULT_HOST = "com.solarized.firedown.drm.host";
    /** True when the answer came from this sheet (not the security sheet's row). */
    public static final String RESULT_FROM_SHEET = "com.solarized.firedown.drm.from_sheet";

    private boolean mReported;

    @NonNull
    public static Bundle args(@NonNull String host, boolean incognito) {
        Bundle args = new Bundle();
        args.putString(RESULT_HOST, host);
        args.putBoolean(Keys.IS_INCOGNITO, incognito);
        return args;
    }

    /** The answer, as the browser expects it from either surface. */
    @NonNull
    public static Bundle result(boolean turnOn, @Nullable String host, boolean fromSheet) {
        Bundle result = new Bundle();
        result.putBoolean(RESULT_TURN_ON, turnOn);
        result.putString(RESULT_HOST, host);
        result.putBoolean(RESULT_FROM_SHEET, fromSheet);
        return result;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_drm_notice, container, false);
        String host = requireArguments().getString(RESULT_HOST);

        TextView site = mView.findViewById(R.id.drm_sheet_site);
        site.setText(getString(R.string.drm_sheet_site, host != null ? host : ""));

        mView.findViewById(R.id.drm_sheet_not_now).setOnClickListener(v -> {
            report(false);
            dismiss();
        });
        mView.findViewById(R.id.drm_sheet_turn_on).setOnClickListener(v -> {
            report(true);
            dismiss();
        });
        return mView;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        report(false);
    }

    private void report(boolean turnOn) {
        if (mReported) {
            return;
        }
        mReported = true;
        String host = requireArguments().getString(RESULT_HOST);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result(turnOn, host, true));
    }
}
