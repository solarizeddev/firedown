package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoResources;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

public class SecurityStateSheetDialogFragment extends BaseBottomResizedDialogFragment
        implements View.OnClickListener, CompoundButton.OnCheckedChangeListener {

    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private GeckoState mGeckoState;
    private CertificateInfoEntity mCertificateInfoEntity;
    private TextView mAdsCounterTextView;
    private MaterialSwitch mAdsSwitch;
    private MaterialSwitch mTrackingSwitch;
    private TextView mTrackingSubtext;
    private TextView mHostText;
    private View mHostCert;
    private AppCompatImageView mTrackingIcon;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);

        Bundle bundle = getArguments();

        if (bundle == null)
            throw new IllegalArgumentException("Bundle null");

        mGeckoState = mIsIncognito
                ? mIncognitoStateViewModel.peekCurrentGeckoState()
                : mGeckoStateViewModel.peekCurrentGeckoState();

        if (mGeckoState == null) {
            dismiss();
            return;
        }

        mCertificateInfoEntity = mGeckoState.getCertificateState();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;

        mView = themedInflater.inflate(R.layout.fragment_dialog_security, container, false);

        mTrackingIcon = mView.findViewById(R.id.tracking_icon);
        mTrackingSwitch = mView.findViewById(R.id.tracking_toogle);
        mTrackingSubtext = mView.findViewById(R.id.tracking_subtext);
        mAdsCounterTextView = mView.findViewById(R.id.ads_counter);
        mAdsSwitch = mView.findViewById(R.id.ads_toogle);
        mHostText = mView.findViewById(R.id.host_secure_text);
        mHostCert = mView.findViewById(R.id.host_secure);

        mTrackingSwitch.setOnCheckedChangeListener(this);
        mAdsSwitch.setOnCheckedChangeListener(this);

        TextView host = mView.findViewById(R.id.host);
        TextView hostUrl = mView.findViewById(R.id.host_url);

        AppCompatImageView hostImage = mView.findViewById(R.id.host_image);

        View hostClear = mView.findViewById(R.id.host_clear);

        hostClear.setOnClickListener(this);
        mHostCert.setOnClickListener(this);

        String url;
        String domain;

        if (mCertificateInfoEntity != null) {
            url = mCertificateInfoEntity.url;
            domain = mCertificateInfoEntity.host;
        } else {
            url = mGeckoState.getEntityUri();
            domain = WebUtils.getDomainName(url);
        }

        mHostCert.setEnabled(mCertificateInfoEntity != null);

        host.setText(GeckoResources.isOnboarding(url) ? GeckoResources.ABOUT_ONBOARDING : mGeckoState.getEntityTitle());
        hostUrl.setText(domain);

        boolean isSecure = mCertificateInfoEntity != null && mCertificateInfoEntity.isSecure;

        hostClear.setEnabled(isUrlValidForCleaning(domain));

        mHostText.setText(isSecure ? R.string.quick_settings_sheet_secure_connection_2 : R.string.quick_settings_sheet_insecure_connection_2);
        mHostText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                isSecure ? R.drawable.encryption_light_24 : R.drawable.no_encryption_light_24, 0, 0, 0);

        loadFavicon(hostImage, domain);

        // Ads count — routed to the correct per-mode stream so the incognito
        // sheet never reflects counts from regular browsing and vice versa.
        // Both streams are backed by the same GeckoUblockHelper singleton;
        // GeckoRuntimeHelper.handleUblockMessage decides which one to post
        // into based on the sending GeckoSession's incognito-ness.
        LiveData<String> adsCountLive = mIsIncognito
                ? mIncognitoStateViewModel.getAdsCount()
                : mGeckoStateViewModel.getAdsCount();

        adsCountLive.observe(getViewLifecycleOwner(), count -> {
            mAdsCounterTextView.setText(String.valueOf(count));
        });

        // Ads filter enabled state is a per-URL whitelist concept (netWhitelist
        // Map in µb), not per-mode. Always read from the regular ViewModel.
        mGeckoStateViewModel.isAdsFilterEnabled().observe(getViewLifecycleOwner(), active -> {
            mAdsSwitch.setChecked(active);
        });

        // Tracking protection — delegate to the correct ViewModel.
        // In incognito mode this uses an ephemeral in-memory set so
        // domain exceptions are never persisted to disk.
        boolean trackingEnabled = mIsIncognito
                ? mIncognitoStateViewModel.isTrackingProtected(mGeckoState.getEntityUri())
                : mGeckoStateViewModel.isTrackingProtected(mGeckoState.getEntityUri());

        updateTrackingUI(trackingEnabled);

        return mView;
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Observe the correct certificate LiveData based on mode
        MutableLiveData<CertificateInfoEntity> certLiveData = mIsIncognito
                ? mIncognitoStateViewModel.getCertificateData()
                : mGeckoStateViewModel.getCertificateData();

        certLiveData.observe(this, certificateInfoEntity -> {
            if (certificateInfoEntity == null || mCertificateInfoEntity != null)
                return;
            mCertificateInfoEntity = certificateInfoEntity;
            boolean isSecure = certificateInfoEntity.isSecure;
            mHostText.setText(isSecure ? R.string.quick_settings_sheet_secure_connection_2 : R.string.quick_settings_sheet_insecure_connection_2);
            mHostText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    isSecure ? R.drawable.encryption_light_24 : R.drawable.no_encryption_light_24, 0, 0, 0);
            mHostCert.setEnabled(true);
        });
    }

    @Override
    public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
        if (!buttonView.isPressed()) return;

        int id = buttonView.getId();
        if (id == R.id.ads_toogle) {
            mGeckoRuntimeHelper.setAds(isChecked);
        } else if (id == R.id.tracking_toogle) {
            if (mIsIncognito) {
                mIncognitoStateViewModel.toggleTrackingProtection(mGeckoState, isChecked);
            } else {
                mGeckoStateViewModel.toggleTrackingProtection(mGeckoState, isChecked);
            }
            updateTrackingUI(isChecked);
        }
    }

    private void updateTrackingUI(boolean isEnabled) {
        mTrackingSwitch.setChecked(isEnabled);
        mTrackingIcon.setImageResource(isEnabled ? R.drawable.ic_shield_24 : R.drawable.ic_shield_privacy_tip_24);
        mTrackingSubtext.setText(isEnabled ?
                R.string.protection_panel_etp_toggle_enabled_description_2 :
                R.string.protection_panel_etp_toggle_disabled_description_2);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.host_clear) {
            Bundle bundle = new Bundle();
            bundle.putString(Keys.ITEM_ID, mCertificateInfoEntity.host);
            bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
            NavigationUtils.navigateSafe(mNavController, R.id.action_security_to_clear, bundle);
        } else if (id == R.id.host_secure) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, mCertificateInfoEntity);
            bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
            NavigationUtils.navigateSafe(mNavController, R.id.action_security_to_cert, bundle);
        }
    }


    private void loadFavicon(AppCompatImageView imageView, String domain) {
        int radius = getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        String fullDomain;
        String iconUrl = mGeckoState.getEntityIcon();
        if (TextUtils.isEmpty(domain)) {
            fullDomain = null;
        } else {
            fullDomain = domain.startsWith("http") ? domain : "https://" + domain;
        }
        GlideHelper.load(iconUrl, fullDomain, imageView, RequestOptions.bitmapTransform(new RoundedCorners(radius)));
    }

    private boolean isUrlValidForCleaning(String domain) {
        return UrlStringUtils.isURLLike(domain) &&
                !UrlStringUtils.isURLResouceLike(domain) &&
                !UrlStringUtils.isAboutBlank(domain);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHostCert = null;
        mHostText = null;
        mAdsSwitch = null;
        mTrackingSwitch = null;
        mAdsCounterTextView = null;
        mTrackingIcon = null;
        mTrackingSubtext = null;
        mView = null;
    }
}