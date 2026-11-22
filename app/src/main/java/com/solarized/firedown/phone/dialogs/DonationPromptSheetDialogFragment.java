package com.solarized.firedown.phone.dialogs;

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
     *  downloaded: a tap on Donate this soon after it appeared was aimed at
     *  the list, not at the button. */
    private static final long TAP_GUARD_MS = 600;

    private long mVisibleSince;

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
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_donation_prompt, container, false);

        mView.findViewById(R.id.donation_prompt_donate).setOnClickListener(v -> {
            if (SystemClock.uptimeMillis() - mVisibleSince < TAP_GUARD_MS) {
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

        mView.findViewById(R.id.donation_prompt_later).setOnClickListener(v -> dismiss());

        return mView;
    }
}
