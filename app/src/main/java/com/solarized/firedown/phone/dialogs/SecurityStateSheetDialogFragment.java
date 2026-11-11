package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
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
import com.solarized.firedown.Preferences;
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
import com.solarized.firedown.utils.DataUris;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SecurityStateSheetDialogFragment extends BaseBottomSheetDialogFragment
        implements View.OnClickListener {

    @Inject
    AddonManager mAddonManager;

    @Inject
    SharedPreferences mSharedPreferences;

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
    /** The blocker rows on screen, by add-on id, in order. */
    private final Map<String, MaterialCardView> mBlockerRows = new LinkedHashMap<>();
    /** Add-ons whose icon was already asked for while this sheet is open. */
    private final Set<String> mIconLoaded = new HashSet<>();
    private int mIconPx;
    private TextView mHostText;
    private View mHostCert;
    private View mAdsStatCard;
    private View mProtectionBanner;
    private TextView mProtectionTitle;
    private MaterialButton mProtectionButton;
    private ImageView mProtectionChevron;
    private ImageView mProtectionIcon;
    /** The page's blocked-request count, as last reported (the banner's subtitle). */
    private int mBlockedCount;
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
        mProtectionBanner = mView.findViewById(R.id.protection_banner);
        mProtectionTitle = mView.findViewById(R.id.protection_title);
        mProtectionButton = mView.findViewById(R.id.protection_button);
        mProtectionChevron = mView.findViewById(R.id.protection_chevron);
        mProtectionIcon = mView.findViewById(R.id.protection_icon);

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
            // A page with no network host (about:, file:, the error page)
            // reports an empty certificate host.
            if (TextUtils.isEmpty(mDomain)) {
                mDomain = WebUtils.getDomainName(mGeckoState.getEntityUri());
            }
        } else {
            url = mGeckoState.getEntityUri();
            mDomain = WebUtils.getDomainName(url);
        }

        mHostCert.setEnabled(mCertificateInfoEntity != null);

        orderGroups();
        bindDrmRow();
        bindSitePermissionsRow();

        // The site leads: it is what a security sheet is about, and the
        // page's title is already on the page behind the sheet. The title
        // follows, dim, and is left out when the page has none.
        // A title that only repeats the site or the address (an image or a
        // PDF titled by its URL) is left out too; with no site to show, the
        // title leads.
        String site = mDomain;
        if (site != null && DataUris.isDataUri(site)) {
            site = DataUris.stripPayload(site);
        }
        String title = mGeckoState.getEntityTitle();
        boolean hasTitle = !UrlStringUtils.isBlankTitle(title)
                && !title.equals(site) && !title.equals(url);
        if (TextUtils.isEmpty(site) && hasTitle) {
            site = title;
            hasTitle = false;
        }
        host.setText(site);
        hostUrl.setText(hasTitle ? title : null);
        hostUrl.setVisibility(hasTitle ? View.VISIBLE : View.GONE);

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
            mBlockedCount = parsed;
            renderBanner();
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
            orderGroups();
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
     * uBlock Origin first; with none, the banner says so and carries Turn
     * on (renderBanner). Rows are
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
        SheetGroups.applyCorners(mBlockersGroup);
        renderBanner();
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
                // The row is where the switch is: Firedown cannot flip a
                // blocker's per-site switch, the tap opens its popup, which can.
                sub = R.string.shield_blocker_off_site_switch;
                break;
            default:
                // Only uBlock says whether it is on for the site; for another
                // blocker, enabled here is all there is to read.
                sub = item.isUblock() ? R.string.shield_blocker_on_site_switch : R.string.shield_blocker_on;
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
                mAddonManager.markShieldClick(item.addon.id, item.state);
                action.click();
            });
        } else {
            row.setOnClickListener(null);
        }
        row.setClickable(usable);
    }

    /**
     * Protection leads the sheet: the address bar's icon is a shield whose
     * state is the blocker's, so the sheet opens on what the icon said. On a
     * page whose connection is not secure (plain http, or a certificate that
     * arrived and is not secure) the connection group leads instead, so
     * "Protection is on" never sits above "Connection is not secure" as
     * reassurance. An https page whose certificate has not arrived yet keeps
     * protection first; the certificate observer calls this again.
     */
    private void orderGroups() {
        if (mView == null) {
            return;
        }
        String url = mGeckoState.getEntityUri();
        boolean insecure = (url != null && url.startsWith("http://"))
                || (mCertificateInfoEntity != null && !mCertificateInfoEntity.isSecure);
        View connection = mView.findViewById(R.id.connection_group);
        View blockers = mView.findViewById(R.id.blockers_group);
        ViewGroup body = (ViewGroup) connection.getParent();
        View lead = insecure ? connection : blockers;
        View follower = insecure ? blockers : connection;
        if (body.indexOfChild(lead) != 0) {
            // The hint stays under the blockers group whichever way round.
            View hint = mView.findViewById(R.id.blockers_hint);
            body.removeView(connection);
            body.removeView(blockers);
            body.removeView(hint);
            int at = 0;
            if (insecure) {
                body.addView(connection, at++);
            }
            body.addView(blockers, at++);
            body.addView(hint, at++);
            if (!insecure) {
                body.addView(connection, at);
            }
        }
        int gap = getResources().getDimensionPixelSize(R.dimen.sheet_group_gap);
        ViewGroup.MarginLayoutParams first = (ViewGroup.MarginLayoutParams) lead.getLayoutParams();
        first.topMargin = 0;
        lead.setLayoutParams(first);
        ViewGroup.MarginLayoutParams second = (ViewGroup.MarginLayoutParams) follower.getLayoutParams();
        second.topMargin = gap;
        follower.setLayoutParams(second);
    }

    /**
     * "DRM is off   [Turn on]" at the head of the connection group, while the page on
     * screen asked for DRM and it is off: the row the shield's dot points
     * to. Turn on answers the way the DRM sheet does, so the browser turns
     * it on and reloads (one implementation, in BrowserFragment).
     */
    private void bindDrmRow() {
        View row = mView.findViewById(R.id.drm_row);
        boolean show = mGeckoState.isDrmRefusedHere()
                && !Preferences.getDRMEnabled(mSharedPreferences);
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        // The DRM row leads its group (the shield's dot points at it); the
        // connection row takes the 2dp row gap only when it follows it.
        View connection = mView.findViewById(R.id.host_secure);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) connection.getLayoutParams();
        lp.topMargin = show ? getResources().getDimensionPixelSize(R.dimen.sheet_row_gap) : 0;
        connection.setLayoutParams(lp);
        SheetGroups.applyCorners(mView.findViewById(R.id.connection_group));
        if (!show) {
            return;
        }
        String host = WebUtils.getDomainName(mGeckoState.getEntityUri());
        mView.findViewById(R.id.drm_row_turn_on).setOnClickListener(v -> {
            getParentFragmentManager().setFragmentResult(DrmSheetDialogFragment.RESULT_KEY,
                    DrmSheetDialogFragment.result(true, host, false));
            dismiss();
        });
    }

    /**
     * "Site permissions · 2 allowed ›" under the connection row, only when
     * the site holds an answer the user gave (SitePermissions decides which).
     * Gecko answers asynchronously; until then, and on a site with nothing
     * stored, the row is absent.
     */
    private void bindSitePermissionsRow() {
        View row = mView.findViewById(R.id.site_permissions_row);
        row.setVisibility(View.GONE);
        SheetGroups.applyCorners(mView.findViewById(R.id.connection_group));
        String url = mGeckoState.getEntityUri();
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return;
        }
        mGeckoRuntimeHelper.loadSitePermissions(url, mIsIncognito, permissions -> {
            if (mView == null || !isAdded() || permissions.isEmpty()) {
                return;
            }
            // The view on screen now: a rotation can rebuild it before Gecko answers.
            View shown = mView.findViewById(R.id.site_permissions_row);
            TextView summary = mView.findViewById(R.id.site_permissions_summary);
            summary.setText(SitePermissionsDialogFragment.summary(getResources(), permissions));
            shown.setVisibility(View.VISIBLE);
            SheetGroups.applyCorners(mView.findViewById(R.id.connection_group));
            shown.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putString(Keys.ITEM_ID, url);
                bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
                NavigationUtils.navigateSafe(mNavController, R.id.action_security_to_site_permissions, bundle);
            });
        });
    }

    /**
     * The protection banner: the shield's verdict (the same
     * AddonManager.shieldState the address bar's shield reads), the page's
     * count, and Turn on when no blocker runs here, the one switch the app
     * can flip itself (a blocker's per-site switch is in its own popup,
     * the row below). A plain row in every state, a grey shield whose glyph
     * says on or off. Called on every count and every add-on
     * change.
     */
    private void renderBanner() {
        if (mProtectionBanner == null || mGeckoState == null || !isAdded()) {
            return;
        }
        int shield = mAddonManager.shieldState(mGeckoState.getGeckoSession(), mIsIncognito,
                mGeckoState.getEntityUri());
        boolean on = shield == AddonPolicy.SHIELD_PROTECTED;
        // On: the count is the title and the verdict the subtitle, so the
        // subtitle keeps one shape while the count comes and goes. The count
        // is blocker-neutral (any blocker, and Gecko's own), so the banner
        // names no add-on: the rows below do.
        if (on) {
            if (mBlockedCount > 0) {
                mProtectionTitle.setText(getResources().getQuantityString(
                        R.plurals.shield_blocked_title, mBlockedCount, mBlockedCount));
            } else {
                mProtectionTitle.setText(R.string.blocked_page_empty);
            }
            mTotalCountTextView.setText(R.string.shield_protection_on);
        } else {
            mProtectionTitle.setText(shield == AddonPolicy.SHIELD_OFF_FOR_SITE
                    ? R.string.shield_protection_off_site : R.string.shield_no_blocker_title);
            // Off: no count. One left from before the user turned the blocker
            // off would contradict the title until the page reloads; the tap
            // still lists the hosts.
            mTotalCountTextView.setText(R.string.shield_no_blocker_summary);
        }

        // A row like the ones under it in both states: the rows' own tone
        // (the incognito one in private tabs), the theme's inks, and a grey
        // shield like the address bar's. The glyph (tick or exclamation)
        // and the words carry the state; the colour on this sheet is the
        // blocker's own logo in the row below. A gradient card, a gradient
        // disc and a coral shield were each louder than a status row should
        // be (canvas boards K and L, on-device).
        int title = MaterialColors.getColor(mProtectionBanner, com.google.android.material.R.attr.colorOnSurface);
        int subtitle = MaterialColors.getColor(mProtectionBanner,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        int tone = mIsIncognito
                ? IncognitoColors.getSurfaceContainerLow(mProtectionBanner.getContext(), true)
                : MaterialColors.getColor(mProtectionBanner,
                com.google.android.material.R.attr.colorSurfaceContainerLow);
        mProtectionTitle.setTextColor(title);
        mTotalCountTextView.setTextColor(subtitle);
        mProtectionChevron.setImageTintList(ColorStateList.valueOf(subtitle));
        // The address bar's protected and reduced shields (its secure-axis
        // variants are the connection row's business here).
        mProtectionIcon.setImageResource(on ? R.drawable.ic_shield_24 : R.drawable.ic_shield_privacy_tip_24);
        mProtectionIcon.setImageTintList(ColorStateList.valueOf(subtitle));

        GradientDrawable background = new GradientDrawable();
        background.setColor(tone);
        float outer = getResources().getDimension(R.dimen.sheet_group_corner_outer);
        float bottom = hasCardAfterBanner() ? getResources().getDimension(R.dimen.sheet_group_corner_inner) : outer;
        background.setCornerRadii(new float[]{outer, outer, outer, outer, bottom, bottom, bottom, bottom});
        mProtectionBanner.setBackground(background);

        if (shield == AddonPolicy.SHIELD_NO_BLOCKER) {
            Set<String> installing = mAddonManager.getInstalling().getValue();
            boolean busy = installing != null && installing.contains(AddonPolicy.UBLOCK_ID);
            mProtectionButton.setText(busy ? R.string.shield_installing : R.string.shield_turn_on);
            mProtectionButton.setEnabled(!busy);
            mProtectionButton.setOnClickListener(v -> mAddonManager.turnOnUblock(mIsIncognito));
            mProtectionButton.setVisibility(View.VISIBLE);
            // The button is the action here; a chevron beside it would leave
            // the title too narrow on a small phone.
            mProtectionChevron.setVisibility(View.GONE);
            setBannerEndPadding(R.dimen.protection_banner_end_button);
        } else {
            mProtectionButton.setOnClickListener(null);
            mProtectionButton.setVisibility(View.GONE);
            mProtectionChevron.setVisibility(View.VISIBLE);
            // The chevron on the rows' chevron column (16dp in).
            setBannerEndPadding(R.dimen.protection_banner_end_chevron);
        }
    }

    private void setBannerEndPadding(int dimen) {
        mProtectionBanner.setPaddingRelative(mProtectionBanner.getPaddingStart(),
                mProtectionBanner.getPaddingTop(),
                getResources().getDimensionPixelSize(dimen),
                mProtectionBanner.getPaddingBottom());
    }

    /** Whether a visible blocker row follows the banner (its bottom corners are then the inner ones). */
    private boolean hasCardAfterBanner() {
        for (int i = 0; i < mBlockersGroup.getChildCount(); i++) {
            View child = mBlockersGroup.getChildAt(i);
            if (child != mAdsStatCard && child instanceof MaterialCardView
                    && child.getVisibility() == View.VISIBLE) {
                return true;
            }
        }
        return false;
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
        mProtectionBanner = null;
        mProtectionTitle = null;
        mProtectionButton = null;
        mProtectionChevron = null;
        mProtectionIcon = null;
        mView = null;
    }
}
