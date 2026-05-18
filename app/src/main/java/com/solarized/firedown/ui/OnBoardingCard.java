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
    }

    private OnBoardingCardListener mCallback;

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

        setVisibility(onboardingVisible ? View.VISIBLE : View.GONE);

    }

    public void setCallback(OnBoardingCardListener listener){
        mCallback = listener;
    }


    @Override
    public void onClick(View v) {
        int id = v.getId();
        if(mCallback != null){
            mCallback.OnBoardingCardClicked(id);
        }
    }
}
