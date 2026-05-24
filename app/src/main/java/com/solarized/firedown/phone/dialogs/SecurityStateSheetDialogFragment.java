package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
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
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoResources;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.TrackingCategory;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SecurityStateSheetDialogFragment extends BaseBottomSheetDialogFragment
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
    private AppCompatImageView mHostImage;
    private String mDomain;
    private String mLastIconUrl;
    private View mBlockedTrackersSummaryRow;
    private TextView mBlockedTrackersSummaryText;
    private boolean mTrackingEnabledForSite;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);

        // Args carry only mIsIncognito (read by BaseBottomSheetDialogFragment).
        // A null bundle means no incognito flag — treat as regular mode rather
        // than crashing.
        mGeckoState = mIsIncognito
                ? mIncognitoStateViewModel.peekCurrentGeckoState()
                : mGeckoStateViewModel.peekCurrentGeckoState();

        if (mGeckoState != null) {
            mCertificateInfoEntity = mGeckoState.getCertificateState();
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mGeckoState == null) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }
        return super.onCreateDialog(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        if (mGeckoState == null) return null;

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
        mBlockedTrackersSummaryRow = mView.findViewById(R.id.blocked_trackers_summary_row);
        mBlockedTrackersSummaryText = mView.findViewById(R.id.blocked_trackers_summary_text);

        // Tap → drill into the per-host detail sheet. The detail sheet
        // pulls its data from the same GeckoState the parent sheet
        // observes, so there's nothing to pass through arguments other
        // than the incognito flag (forwarded automatically by the
        // BaseBottomSheetDialogFragment arg pipeline).
        mBlockedTrackersSummaryRow.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
            NavigationUtils.navigateSafe(mNavController,
                    R.id.action_security_to_blocked_trackers_detail,
                    R.id.dialog_security_info,
                    args);
        });

        mTrackingSwitch.setOnCheckedChangeListener(this);
        mAdsSwitch.setOnCheckedChangeListener(this);

        TextView host = mView.findViewById(R.id.host);
        TextView hostUrl = mView.findViewById(R.id.host_url);

        // Field-scoped: the tabs LiveData observer in onViewCreated
        // re-binds the favicon when the icon arrives after the sheet
        // has already painted (favicon resolution is async and routinely
        // races the user opening this dialog).
        mHostImage = mView.findViewById(R.id.host_image);

        View hostClear = mView.findViewById(R.id.host_clear);

        hostClear.setOnClickListener(this);
        mHostCert.setOnClickListener(this);

        String url;
        if (mCertificateInfoEntity != null) {
            url = mCertificateInfoEntity.url;
            mDomain = mCertificateInfoEntity.host;
        } else {
            url = mGeckoState.getEntityUri();
            mDomain = WebUtils.getDomainName(url);
        }

        mHostCert.setEnabled(mCertificateInfoEntity != null);

        host.setText(GeckoResources.isOnboarding(url) ? GeckoResources.ABOUT_ONBOARDING : mGeckoState.getEntityTitle());
        hostUrl.setText(mDomain);

        boolean isSecure = mCertificateInfoEntity != null && mCertificateInfoEntity.isSecure;

        hostClear.setEnabled(isUrlValidForCleaning(mDomain));

        mHostText.setText(isSecure ? R.string.quick_settings_sheet_secure_connection_2 : R.string.quick_settings_sheet_insecure_connection_2);
        mHostText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                isSecure ? R.drawable.encryption_light_24 : R.drawable.no_encryption_light_24, 0, 0, 0);

        mLastIconUrl = mGeckoState.getEntityIcon();
        loadFavicon(mHostImage, mDomain);

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

        // Per-page blocked-trackers breakdown. The repository's LiveData
        // is "current tab's counts" but it can carry a stale value from
        // whichever tab last emitted before this one was activated, so
        // ask the ViewModel to re-emit the snapshot for *this* tab
        // before subscribing.
        if (mIsIncognito) {
            mIncognitoStateViewModel.refreshBlockedTrackerCounts();
            mIncognitoStateViewModel.getBlockedTrackerCounts()
                    .observe(getViewLifecycleOwner(), this::renderBlockedTrackerCounts);
        } else {
            mGeckoStateViewModel.refreshBlockedTrackerCounts();
            mGeckoStateViewModel.getBlockedTrackerCounts()
                    .observe(getViewLifecycleOwner(), this::renderBlockedTrackerCounts);
        }

        return mView;
    }


    /**
     * Update the summary row with the page's running tracker total. The
     * row itself opens the per-host detail sheet on tap; we don't render
     * the per-category breakdown here any more.
     *
     * <p>Hidden when the user has added a tracking exception for this
     * site (counts wouldn't be meaningful — events stop firing) or when
     * the page hasn't blocked anything yet. The toggle row above keeps
     * showing the existing subtext in both cases, so the sheet stays
     * useful.
     */
    private void renderBlockedTrackerCounts(Map<TrackingCategory, Integer> counts) {
        if (mBlockedTrackersSummaryRow == null) return;

        int total = 0;
        if (counts != null) {
            for (Integer v : counts.values()) {
                if (v != null) total += v;
            }
        }

        if (!mTrackingEnabledForSite || total == 0) {
            mBlockedTrackersSummaryRow.setVisibility(View.GONE);
            return;
        }

        mBlockedTrackersSummaryRow.setVisibility(View.VISIBLE);
        mBlockedTrackersSummaryText.setText(getResources().getQuantityString(
                R.plurals.blocked_trackers_summary, total, total));
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

        // Favicon resolution is async and routinely races the user opening
        // this sheet — the GeckoSession's icon callback can land seconds
        // later. Observe the existing tabs LiveData (already updated by
        // GeckoStateDataRepository.updateIcon → notifyTabs) and rebind the
        // host_image whenever our entity's icon string actually changes.
        // The equality short-circuit makes per-emission work O(1) so other
        // tab-list events (new tab / close tab / title update) are free.
        LiveData<List<GeckoStateEntity>> tabsLive = mIsIncognito
                ? mIncognitoStateViewModel.getTabs()
                : mGeckoStateViewModel.getTabs();

        tabsLive.observe(getViewLifecycleOwner(), tabs -> {
            if (tabs == null || mGeckoState == null || mHostImage == null) return;
            int id = mGeckoState.getEntityId();
            for (GeckoStateEntity entity : tabs) {
                if (entity.getId() != id) continue;
                String icon = entity.getIcon();
                if (!Objects.equals(icon, mLastIconUrl)) {
                    mLastIconUrl = icon;
                    mGeckoState.setEntityIcon(icon);
                    loadFavicon(mHostImage, mDomain);
                }
                break;
            }
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
        mTrackingEnabledForSite = isEnabled;
        mTrackingSwitch.setChecked(isEnabled);
        mTrackingIcon.setImageResource(isEnabled ? R.drawable.ic_shield_24 : R.drawable.ic_shield_privacy_tip_24);
        mTrackingSubtext.setText(isEnabled ?
                R.string.protection_panel_etp_toggle_enabled_description_2 :
                R.string.protection_panel_etp_toggle_disabled_description_2);
        // When the user flips the per-site toggle off we drop the
        // summary row immediately — its counts are stale the moment we
        // stop applying ETP, and re-showing them would look like an
        // exception is still being protected.
        if (!isEnabled && mBlockedTrackersSummaryRow != null) {
            mBlockedTrackersSummaryRow.setVisibility(View.GONE);
        }
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
        mHostImage = null;
        mBlockedTrackersSummaryRow = null;
        mBlockedTrackersSummaryText = null;
        mView = null;
    }
}