package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.donation.DonationPrompt;
import com.solarized.firedown.phone.SettingsActivity;

/**
 * The occasional donation ask, shown over Downloads right after a download
 * finished (see {@link com.solarized.firedown.donation.DonationPromptPolicy}
 * for when). Donate opens the website's donate page in a tab and pauses the
 * asks for a year; Not now, Back or a swipe just close it.
 */
public class DonationPromptSheetDialogFragment extends BaseBottomSheetDialogFragment {

    /** The sheet rises while the user reaches for the file they just
     *  downloaded: a tap this soon after it appeared was aimed at the list.
     *  On Donate it would pause the asks for a year, and on Not now or the
     *  scrim it would close the sheet unseen and use up one of the asks. */
    private static final long TAP_GUARD_MS = 600;

    private long mVisibleSince;

    private final Runnable mAllowOutsideTap = () -> {
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.setCanceledOnTouchOutside(true);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            // Counted here, when it is really on its way to the screen, not
            // before the navigation that may not have opened it.
            DonationPrompt.markShown(requireContext());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mVisibleSince = SystemClock.uptimeMillis();
        Dialog dialog = getDialog();
        if (dialog != null && mView != null) {
            dialog.setCanceledOnTouchOutside(false);
            mView.removeCallbacks(mAllowOutsideTap);
            mView.postDelayed(mAllowOutsideTap, TAP_GUARD_MS);
        }
    }

    @Override
    public void onStop() {
        if (mView != null) {
            mView.removeCallbacks(mAllowOutsideTap);
        }
        super.onStop();
    }

    private boolean tooSoon() {
        return SystemClock.uptimeMillis() - mVisibleSince < TAP_GUARD_MS;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_donation_prompt, container, false);

        mView.findViewById(R.id.donation_prompt_donate).setOnClickListener(v -> {
            if (tooSoon()) {
                return;
            }
            BaseActivity activity = mActivity;
            String url = getString(R.string.settings_donate_url);
            dismiss();
            if (activity == null) {
                return;
            }
            DonationPrompt.markDonateTapped(activity);
            SettingsActivity.openInBrowser(activity, url);
        });

        mView.findViewById(R.id.donation_prompt_later).setOnClickListener(v -> {
            if (!tooSoon()) {
                dismiss();
            }
        });

        return mView;
    }
}
