package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.Keys;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

import java.util.List;
import java.util.Objects;

public class SecurityStateSheetDialogFragment extends BaseBottomSheetDialogFragment
        implements View.OnClickListener {

    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private GeckoState mGeckoState;
    private CertificateInfoEntity mCertificateInfoEntity;
    // Summary count = uBlock ad/filter blocks for this page (getAdsCount).
    // Matches what the drill-in (BlockedAdsDetailDialogFragment) itemizes, so
    // the number always equals the list length. (Gecko's ETP still blocks
    // globally at its Settings level; it has no per-site switch here any
    // more and no per-page number — see the layout comment.)
    private TextView mTotalCountTextView;
    private MaterialSwitch mAdsSwitch;
    /** True while the switch is being set from the ViewModel, so the
     *  change listener doesn't echo a programmatic bind back as a user
     *  toggle (the old isPressed() guard, which a card tap → toggle()
     *  would also have failed). */
    private boolean mBindingAdsSwitch;
    private TextView mHostText;
    private View mHostCert;
    private View mAdsStatCard;
    private AppCompatImageView mHostImage;
    private String mDomain;
    private String mLastIconUrl;

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

        mTotalCountTextView = mView.findViewById(R.id.security_total_count);
        mAdsSwitch = mView.findViewById(R.id.ads_toogle);
        mHostText = mView.findViewById(R.id.host_secure_text);
        mHostCert = mView.findViewById(R.id.host_secure);
        mAdsStatCard = mView.findViewById(R.id.ads_stat_card);

        // The single "N blocked on this page" summary row drills into the
        // blocked-host list (uBlock's per-page blocked hosts — the meaningful
        // "what got blocked here"). The nav action popUpTo dialog_security_info
        // is inclusive, so back from the detail returns to the browser rather
        // than re-opening this sheet; the detail fragment paints its own
        // "nothing blocked yet" empty state, so the row opens regardless of
        // count.
        mAdsStatCard.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
            NavigationUtils.navigateSafe(mNavController,
                    R.id.action_security_to_blocked_ads_detail,
                    R.id.dialog_security_info,
                    args);
        });

        // One switch, one engine: uBlock's per-host whitelist. The whole
        // CARD is the tap target (Android-Settings style), so a tap anywhere
        // on the row flips the switch; both routes land in the same listener.
        mAdsSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (mBindingAdsSwitch) return;
            mGeckoRuntimeHelper.setAds(isChecked);
        });
        View adsCard = mView.findViewById(R.id.ads_card);
        adsCard.setOnClickListener(v -> {
            if (mAdsSwitch != null) mAdsSwitch.toggle();
        });

        TextView host = mView.findViewById(R.id.host);
        TextView hostUrl = mView.findViewById(R.id.host_url);

        // Field-scoped: the tabs LiveData observer in onViewCreated
        // re-binds the favicon when the icon arrives after the sheet
        // has already painted (favicon resolution is async and routinely
        // races the user opening this dialog).
        mHostImage = mView.findViewById(R.id.host_image);

        // bg_popup_favicon resolves to ?attr/colorSurfaceVariant which
        // lands on a gray-blue tone — fine on the standard sheet bg
        // but a clash on the incognito sheet's purple container_high.
        // Tint the tile to the purple-family container_highest only
        // when incognito so the chip stays in family.
        if (mIsIncognito) {
            mHostImage.setBackgroundTintList(ColorStateList.valueOf(
                    IncognitoColors.getSurfaceContainerHighest(mHostImage.getContext(), true)));
        }

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

        host.setText(mGeckoState.getEntityTitle());
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
            int parsed = 0;
            try { parsed = Integer.parseInt(count.trim()); } catch (NumberFormatException ignored) { }
            if (mTotalCountTextView != null) {
                mTotalCountTextView.setText(String.valueOf(parsed));
            }
        });

        // Ads filter enabled state is a per-URL whitelist concept (netWhitelist
        // Map in µb), not per-mode. Always read from the regular ViewModel.
        mGeckoStateViewModel.isAdsFilterEnabled().observe(getViewLifecycleOwner(), active -> {
            if (mAdsSwitch == null) return;
            mBindingAdsSwitch = true;
            mAdsSwitch.setChecked(active);
            mBindingAdsSwitch = false;
        });

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
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.host_clear) {
            // Use mDomain, not mCertificateInfoEntity.host: the clear row is
            // enabled on isUrlValidForCleaning(mDomain) regardless of whether
            // a certificate has resolved, so the cert entity can be null here
            // (http pages, or before the async cert callback lands). mDomain is
            // always populated (cert host when present, else derived from the
            // URL) and is the value cleaning operates on.
            Bundle bundle = new Bundle();
            bundle.putString(Keys.ITEM_ID, mDomain);
            bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
            NavigationUtils.navigateSafe(mNavController, R.id.action_security_to_clear, bundle);
        } else if (id == R.id.host_secure) {
            // Connection drill-down needs the cert. The row is enabled only
            // when the cert exists (mHostCert.setEnabled(... != null)), but
            // guard anyway so a race can't crash.
            if (mCertificateInfoEntity == null) return;
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
        mTotalCountTextView = null;
        mHostImage = null;
        mAdsStatCard = null;
        mView = null;
    }
}
