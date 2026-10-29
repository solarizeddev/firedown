package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
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
 * for when). Donate opens the website's donate page in a tab and ends the
 * asks for good; Not now, Back or a swipe just close it.
 */
public class DonationPromptSheetDialogFragment extends BaseBottomSheetDialogFragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_donation_prompt, container, false);

        mView.findViewById(R.id.donation_prompt_donate).setOnClickListener(v -> {
            BaseActivity activity = mActivity;
            String url = getString(R.string.settings_donate_url);
            dismiss();
            if (activity == null) {
                return;
            }
            DonationPrompt.markDonated(activity);
            SettingsActivity.openInBrowser(activity, url);
        });

        mView.findViewById(R.id.donation_prompt_later).setOnClickListener(v -> dismiss());

        return mView;
    }
}
