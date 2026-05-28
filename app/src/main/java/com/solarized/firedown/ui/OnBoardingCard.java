package com.solarized.firedown.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;

/**
 * Home-screen launchpad card. Rather than explaining the Fire-Button, it
 * offers a few popular sites to try — tapping a chip opens that site so the
 * user discovers the button by using it. The card is shown until the user's
 * first finished download (HomeFragment retires it via ONBOARDING_INFO) or
 * until they dismiss it with the close button.
 */
public class OnBoardingCard extends FrameLayout implements View.OnClickListener {

    public OnBoardingCard(@NonNull Context context) {
        super(context);
        init(context);
    }

    public OnBoardingCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OnBoardingCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public OnBoardingCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }


    public interface OnBoardingCardListener {
        /** Close button tapped. {@code id} is the dismissed view's id. */
        void OnBoardingCardClicked(int id);

        /** A "try it on" site chip was tapped; open the given URL. */
        void onOnboardingSiteClicked(String url);
    }

    private OnBoardingCardListener mCallback;

    private void init(Context context) {

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.fragment_home_onboarding, this, true);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        boolean onboardingVisible = sharedPreferences.getBoolean(Preferences.ONBOARDING_INFO, true);

        v.findViewById(R.id.onboarding_remove).setOnClickListener(this);

        wireChip(v, R.id.chip_youtube,   "https://m.youtube.com");
        wireChip(v, R.id.chip_reddit,    "https://www.reddit.com");
        wireChip(v, R.id.chip_instagram, "https://www.instagram.com");
        wireChip(v, R.id.chip_tiktok,    "https://www.tiktok.com");
        wireChip(v, R.id.chip_x,         "https://x.com");

        setVisibility(onboardingVisible ? View.VISIBLE : View.GONE);
    }

    private void wireChip(View root, int id, String url) {
        View chip = root.findViewById(id);
        if (chip != null) {
            chip.setOnClickListener(view -> {
                if (mCallback != null) mCallback.onOnboardingSiteClicked(url);
            });
        }
    }

    public void setCallback(OnBoardingCardListener listener) {
        mCallback = listener;
    }


    @Override
    public void onClick(View v) {
        if (mCallback != null) {
            mCallback.OnBoardingCardClicked(v.getId());
        }
    }
}
