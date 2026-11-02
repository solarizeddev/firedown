package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonIcons;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPolicy;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.Keys;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.SheetGroups;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SecurityStateSheetDialogFragment extends BaseBottomSheetDialogFragment
        implements View.OnClickListener {

    /** The row that stands for "no ad blocker runs here" (it has no add-on id). */
    private static final String NO_BLOCKER_ROW = "";

    @Inject
    AddonManager mAddonManager;

    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private GeckoState mGeckoState;
    private CertificateInfoEntity mCertificateInfoEntity;
    // Requests refused on this page by any blocker (the capture extension's
    // count, getAdsCount) — the drill-in (BlockedAdsDetailDialogFragment)
    // lists their hosts.
    private TextView mTotalCountTextView;
    private ViewGroup mBlockersGroup;
    private TextView mBlockersHint;
    /** The blocker rows on screen, by add-on id ({@link #NO_BLOCKER_ROW} for none), in order. */
    private final Map<String, MaterialCardView> mBlockerRows = new LinkedHashMap<>();
    /** Add-ons whose icon was already asked for while this sheet is open. */
    private final Set<String> mIconLoaded = new HashSet<>();
    private int mIconPx;
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
        mBlockersGroup = mView.findViewById(R.id.blockers_group);
        mBlockersHint = mView.findViewById(R.id.blockers_hint);
        mBlockerRows.clear();
        mIconLoaded.clear();
        mIconPx = getResources().getDimensionPixelSize(R.dimen.addon_icon_size);
        mHostText = mView.findViewById(R.id.host_secure_text);
        mHostCert = mView.findViewById(R.id.host_secure);
        mAdsStatCard = mView.findViewById(R.id.ads_stat_card);

        // The "N blocked on this page" summary row drills into the page's
        // refused hosts. The nav action popUpTo dialog_security_info
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

        // The page count — per mode, so the incognito sheet never reflects
        // counts from regular browsing and vice versa.
        LiveData<String> adsCountLive = mIsIncognito
                ? mIncognitoStateViewModel.getAdsCount()
                : mGeckoStateViewModel.getAdsCount();

        adsCountLive.observe(getViewLifecycleOwner(), count -> {
            int parsed = 0;
            try { parsed = Integer.parseInt(count.trim()); } catch (NumberFormatException ignored) { }
            if (mTotalCountTextView != null) {
                mTotalCountTextView.setText(blockedSummary(parsed));
            }
        });

        // The blocker rows follow every button change (counts, "(off)") and
        // every add-on change; the "Turn on" row follows the install.
        mAddonManager.getActionsVersion().observe(getViewLifecycleOwner(), version -> renderBlockers());
        mAddonManager.getInstalling().observe(getViewLifecycleOwner(), installing -> renderBlockers());

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

    /**
     * One row per ad blocker that runs here (AddonManager.blockersFor),
     * uBlock Origin first, or "No ad blocker is on" with Turn on. Rows are
     * updated in place while the same add-ons are listed: buttons change many
     * times a page, and rebuilding restarted every icon from blank.
     */
    private void renderBlockers() {
        if (mBlockersGroup == null || mGeckoState == null || !isAdded()) {
            return;
        }
        GeckoSession session = mGeckoState.getGeckoSession();
        List<AddonManager.BlockerItem> items =
                mAddonManager.blockersFor(session, mIsIncognito, mGeckoState.getEntityUri());
        int running = 0;
        for (AddonManager.BlockerItem item : items) {
            if (item.state != AddonManager.BlockerItem.NOT_IN_PRIVATE) {
                running++;
            }
        }
        List<String> keys = new ArrayList<>();
        for (AddonManager.BlockerItem item : items) {
            keys.add(item.addon.id);
        }
        if (running == 0) {
            // Turn on is offered beside a blocker that only lacks private
            // tabs too: it brings back uBlock, which Firedown allows there.
            keys.add(NO_BLOCKER_ROW);
        }
        if (!keys.equals(new ArrayList<>(mBlockerRows.keySet()))) {
            for (MaterialCardView row : mBlockerRows.values()) {
                mBlockersGroup.removeView(row);
            }
            mBlockerRows.clear();
            LayoutInflater inflater = LayoutInflater.from(mBlockersGroup.getContext());
            for (String key : keys) {
                MaterialCardView row = (MaterialCardView) inflater.inflate(R.layout.item_blocker_row,
                        mBlockersGroup, false);
                mBlockersGroup.addView(row);
                mBlockerRows.put(key, row);
            }
            // Every row is new: every icon loads again.
            mIconLoaded.clear();
        }
        for (AddonManager.BlockerItem item : items) {
            MaterialCardView row = mBlockerRows.get(item.addon.id);
            if (row != null) {
                bindBlocker(row, item);
            }
        }
        MaterialCardView none = mBlockerRows.get(NO_BLOCKER_ROW);
        if (none != null) {
            bindNoBlocker(none);
        }
        SheetGroups.applyCorners(mBlockersGroup);
        if (running > 1) {
            mBlockersHint.setText(getResources().getQuantityString(
                    R.plurals.shield_blockers_many, running, running));
            mBlockersHint.setVisibility(View.VISIBLE);
        } else {
            mBlockersHint.setVisibility(View.GONE);
        }
    }

    private void bindBlocker(@NonNull MaterialCardView row, @NonNull AddonManager.BlockerItem item) {
        ImageView icon = row.findViewById(R.id.blocker_icon);
        TextView title = row.findViewById(R.id.blocker_title);
        TextView subtitle = row.findViewById(R.id.blocker_subtitle);
        MaterialButton tonal = row.findViewById(R.id.blocker_button_tonal);
        row.findViewById(R.id.blocker_button).setVisibility(View.GONE);
        View chevron = row.findViewById(R.id.blocker_chevron);
        WebExtension.Action action = item.action;

        title.setText(item.addon.name);
        int sub;
        switch (item.state) {
            case AddonManager.BlockerItem.NOT_IN_PRIVATE:
                sub = R.string.shield_blocker_not_private;
                break;
            case AddonManager.BlockerItem.OFF_FOR_SITE:
                sub = R.string.shield_blocker_off_site;
                break;
            default:
                // Only uBlock says whether it is on for the site; for another
                // blocker, enabled here is all there is to read.
                sub = item.isUblock() ? R.string.shield_blocker_on_site : R.string.shield_blocker_on;
                break;
        }
        // Every row carries its status line, so the rows read alike. No
        // count: the add-on's own badge measures something other than the
        // page total above it, and it is in the add-on's popup.
        subtitle.setText(sub);
        subtitle.setVisibility(View.VISIBLE);

        if (mIconLoaded.add(item.addon.id)) {
            AddonIcons.load(requireContext(), action != null && action.icon != null ? action.icon : item.addon.icon,
                    mIconPx, d -> {
                        if (isAdded()) {
                            icon.setImageDrawable(d);
                        }
                    });
        }
        icon.setImageTintList(null);

        if (item.state == AddonManager.BlockerItem.NOT_IN_PRIVATE) {
            String id = item.addon.id;
            tonal.setText(R.string.shield_blocker_allow);
            tonal.setVisibility(View.VISIBLE);
            tonal.setOnClickListener(v -> mAddonManager.setAllowedInPrivate(id, true));
            chevron.setVisibility(View.GONE);
            row.setOnClickListener(null);
            row.setClickable(false);
            return;
        }
        tonal.setVisibility(View.GONE);
        tonal.setOnClickListener(null);
        boolean usable = action != null && !Boolean.FALSE.equals(action.enabled);
        chevron.setVisibility(usable ? View.VISIBLE : View.GONE);
        if (usable) {
            row.setOnClickListener(v -> {
                // The add-on's own button: its popup (uBlock's per-site
                // switch lives there), which the browser shows in a sheet
                // once this one is gone.
                NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_security_info);
                action.click();
            });
        } else {
            row.setOnClickListener(null);
        }
        row.setClickable(usable);
    }

    /**
     * "27 requests blocked on this page", the number in bold: one plural, so
     * the words agree with the count in every language.
     */
    @NonNull
    private CharSequence blockedSummary(int count) {
        String number = String.format(Locale.getDefault(), "%d", count);
        String text = getResources().getQuantityString(R.plurals.blocked_ads_summary, count, count);
        SpannableString styled = new SpannableString(text);
        int at = text.indexOf(number);
        if (at >= 0) {
            styled.setSpan(new StyleSpan(Typeface.BOLD), at, at + number.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    private void bindNoBlocker(@NonNull MaterialCardView row) {
        ImageView icon = row.findViewById(R.id.blocker_icon);
        icon.setImageResource(R.drawable.ic_shield_privacy_tip_24);
        icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(icon,
                com.google.android.material.R.attr.colorOnSurfaceVariant)));
        ((TextView) row.findViewById(R.id.blocker_title)).setText(R.string.shield_no_blocker_title);
        TextView subtitle = row.findViewById(R.id.blocker_subtitle);
        subtitle.setText(R.string.shield_no_blocker_summary);
        subtitle.setVisibility(View.VISIBLE);
        row.findViewById(R.id.blocker_button_tonal).setVisibility(View.GONE);
        row.findViewById(R.id.blocker_chevron).setVisibility(View.GONE);
        MaterialButton button = row.findViewById(R.id.blocker_button);
        Set<String> installing = mAddonManager.getInstalling().getValue();
        boolean busy = installing != null && installing.contains(AddonPolicy.UBLOCK_ID);
        button.setText(busy ? R.string.shield_installing : R.string.shield_turn_on);
        button.setEnabled(!busy);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> mAddonManager.turnOnUblock(mIsIncognito));
        row.setOnClickListener(null);
        row.setClickable(false);
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
        GlideHelper.load(iconUrl, fullDomain, imageView, RequestOptions.bitmapTransform(new RoundedCorners(radius)), !mIsIncognito);
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
        mTotalCountTextView = null;
        mBlockersGroup = null;
        mBlockersHint = null;
        mBlockerRows.clear();
        mHostImage = null;
        mAdsStatCard = null;
        mView = null;
    }
}
