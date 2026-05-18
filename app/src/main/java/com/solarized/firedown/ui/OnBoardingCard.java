package com.solarized.firedown.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;

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


    public interface OnBoardingCardListener{
        void OnBoardingCardClicked(int id);
        /**
         * Fired when the user taps one of the 'Try Firedown on'
         * supported-site chips. URL is read from the chip's
         * android:tag — host fragment is expected to navigate the
         * browser there (typically via the same openUri() flow as
         * the empty-home paste hero). Default no-op so existing
         * implementations don't have to opt in.
         */
        default void OnBoardingSiteClicked(@NonNull String url) {}
    }

    private OnBoardingCardListener mCallback;

    /** Chip ids carrying android:tag URLs for the supported-sites strip. */
    private static final int[] SITE_CHIP_IDS = {
            R.id.onboarding_site_youtube,
            R.id.onboarding_site_reddit,
            R.id.onboarding_site_instagram,
            R.id.onboarding_site_tiktok,
            R.id.onboarding_site_x,
    };

    private void init(Context context){

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.fragment_home_onboarding, this, true);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        boolean onboardingVisible = sharedPreferences.getBoolean(Preferences.ONBOARDING_INFO, true);

        View onboardingButton = v.findViewById(R.id.onboarding_remove);

        View onboardingCard = v.findViewById(R.id.onboarding_card);

        onboardingCard.setOnClickListener(this);

        onboardingButton.setOnClickListener(this);

        TextView onboardingTitle = v.findViewById(R.id.onboarding_title);

        onboardingTitle.setText(HtmlCompat.fromHtml(context.getString(R.string.info_welcome), HtmlCompat.FROM_HTML_MODE_COMPACT));

        for (int chipId : SITE_CHIP_IDS) {
            View chip = v.findViewById(chipId);
            if (chip != null) chip.setOnClickListener(this);
        }

        setVisibility(onboardingVisible ? View.VISIBLE : View.GONE);

    }

    public void setCallback(OnBoardingCardListener listener){
        mCallback = listener;
    }


    @Override
    public void onClick(View v) {
        if (mCallback == null) return;
        int id = v.getId();
        // Site chips carry their URL in android:tag — route through the
        // dedicated site-click callback so the host fragment can
        // navigate cleanly. Everything else (card body tap, dismiss X)
        // still uses the legacy id-based callback.
        Object tag = v.getTag();
        boolean isSiteChip = tag instanceof String;
        if (isSiteChip) {
            for (int chipId : SITE_CHIP_IDS) {
                if (chipId == id) {
                    mCallback.OnBoardingSiteClicked((String) tag);
                    return;
                }
            }
        }
        mCallback.OnBoardingCardClicked(id);
    }
}
