package com.solarized.firedown.phone.dialogs;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonIcons;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPolicy;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.SheetGroups;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.Image;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The ⋮ sheet's Add-ons page: each add-on running in this tab, with its
 * button as this page sees it. A tap runs the button (the add-on's own
 * click, or its popup, which the browser then shows in a sheet). Repaints
 * when an add-on changes its badge or title while the sheet is open.
 */
@AndroidEntryPoint
public class AddonActionsSheetDialogFragment extends BaseBottomSheetDialogFragment {

    @Inject
    AddonManager mAddonManager;

    private GeckoState mGeckoState;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private LinearLayout mList;
    private TextView mEmpty;
    private int mIconPx;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GeckoStateViewModel regular = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        IncognitoStateViewModel incognito = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mGeckoState = mIsIncognito ? incognito.peekCurrentGeckoState() : regular.peekCurrentGeckoState();
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_addon_actions, container, false);
        mList = mView.findViewById(R.id.addon_actions_list);
        // A new view (rotation): the rows of the old one are gone with it.
        mRows.clear();
        mIconLoaded.clear();
        mEmpty = mView.findViewById(R.id.addon_actions_empty);
        mIconPx = getResources().getDimensionPixelSize(R.dimen.addon_icon_size);
        mView.findViewById(R.id.addon_actions_manage).setOnClickListener(v -> {
            OptionEntity entity = new OptionEntity();
            entity.setId(R.id.addon_actions_manage);
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_addon_actions);
            mBrowserDialogViewModel.onOptionSelected(entity);
        });
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAddonManager.getActionsVersion().observe(getViewLifecycleOwner(), version -> render());
        mAddonManager.getUpdateAttention().observe(getViewLifecycleOwner(), ids -> render());
    }

    /** The rows on screen, by add-on id, in the order shown. */
    private final Map<String, MaterialCardView> mRows = new LinkedHashMap<>();
    /** Add-ons whose icon was already asked for while this sheet is open. */
    private final Set<String> mIconLoaded = new HashSet<>();

    /**
     * Repaints on every badge or title change. Rows are updated IN PLACE
     * when the same add-ons are listed (an add-on like Privacy Badger
     * changes its count many times a page): rebuilding them restarted every
     * icon from blank, a visible flicker. The icon is loaded once per
     * add-on while the sheet is open.
     */
    private void render() {
        if (mList == null || !isAdded()) {
            return;
        }
        GeckoSession session = mGeckoState == null ? null : mGeckoState.getGeckoSession();
        List<AddonManager.ActionItem> items = mAddonManager.actionsFor(session, mIsIncognito);
        List<String> ids = new ArrayList<>();
        for (AddonManager.ActionItem item : items) {
            ids.add(item.addon.id);
        }
        if (!ids.equals(new ArrayList<>(mRows.keySet()))) {
            mList.removeAllViews();
            mRows.clear();
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (AddonManager.ActionItem item : items) {
                MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.item_addon_action, mList, false);
                mList.addView(card);
                mRows.put(item.addon.id, card);
            }
            mIconLoaded.retainAll(ids);
        }
        for (AddonManager.ActionItem item : items) {
            MaterialCardView card = mRows.get(item.addon.id);
            if (card != null) {
                bindRow(card, item);
            }
        }
        mList.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        bindManageRow(ids);
        SheetGroups.applyCorners(mList);
        SheetGroups.applyCorners(mView.findViewById(R.id.addon_actions_manage_group));
    }

    /**
     * An add-on whose update waits but which this page does not list (it is
     * off, or not allowed in private tabs): the dot that brought the user
     * here goes on "Manage add-ons", where Settings names it.
     */
    private void bindManageRow(@NonNull List<String> listed) {
        View manage = mView.findViewById(R.id.addon_actions_manage);
        View dot = mView.findViewById(R.id.addon_actions_manage_dot);
        boolean elsewhere = false;
        List<String> attention = mAddonManager.getUpdateAttention().getValue();
        if (attention != null) {
            for (String id : attention) {
                if (!listed.contains(id)) {
                    elsewhere = true;
                    break;
                }
            }
        }
        dot.setVisibility(elsewhere ? View.VISIBLE : View.GONE);
        manage.setContentDescription(elsewhere
                ? getString(R.string.addons_manage) + ", " + getString(R.string.addons_update_review) : null);
    }

    /** The add-on's waiting update: Settings opens its page and puts the question. */
    private void openReview(@NonNull String addonId) {
        OptionEntity entity = new OptionEntity();
        entity.setId(R.id.addon_review_update);
        entity.setAction(addonId);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_addon_actions);
        mBrowserDialogViewModel.onOptionSelected(entity);
    }

    /** uBlock also says "(off)" on pages it never filters (about:, an add-on's page): not a site the user turned it off for. */
    private boolean onWebPage() {
        String url = mGeckoState == null ? null : mGeckoState.getEntityUri();
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private void bindRow(@NonNull MaterialCardView card, @NonNull AddonManager.ActionItem item) {
        ImageView icon = card.findViewById(R.id.addon_action_icon);
        TextView title = card.findViewById(R.id.addon_action_title);
        TextView subtitle = card.findViewById(R.id.addon_action_subtitle);
        TextView badge = card.findViewById(R.id.addon_action_badge);

        WebExtension.Action action = item.action;
        String name = item.addon.name;
        // The add-on's name leads; the button's own title only says something
        // when it differs from the name and the badge. uBlock titles its button
        // "uBlock Origin (23)" per tab, which put the count twice on one row
        // under the name repeated as a subtitle.
        title.setText(name);

        String sub = null;
        boolean usable = action != null && !Boolean.FALSE.equals(action.enabled);
        // Its update asks for new permissions and the user has not seen the
        // question: the row says so and opens it, instead of the add-on's button.
        boolean review = mAddonManager.needsUpdateAttention(item.addon.id);
        if (review) {
            sub = getString(R.string.addons_update_review);
        } else if (action == null) {
            sub = getString(R.string.addons_no_button);
        } else if (!usable) {
            sub = getString(R.string.addons_not_here);
        } else if (AddonPolicy.saysOffForSite(item.addon.id, action.title)) {
            // uBlock also says "(off)" on pages it never filters: nothing to say there.
            sub = onWebPage() ? getString(R.string.shield_blocker_off_site) : null;
        } else if (AddonPolicy.actionTitleSaysMore(action.title, name, action.badgeText)) {
            sub = action.title;
        }
        subtitle.setText(sub);
        subtitle.setVisibility(sub != null ? View.VISIBLE : View.GONE);

        if (action != null && !TextUtils.isEmpty(action.badgeText)) {
            // The layout's ink, kept so an add-on that stops setting its own
            // badge colors falls back to it on a row reused in place.
            ColorStateList defaultInk = (ColorStateList) badge.getTag();
            if (defaultInk == null) {
                defaultInk = badge.getTextColors();
                badge.setTag(defaultInk);
            }
            badge.setText(action.badgeText);
            badge.setBackgroundTintList(action.badgeBackgroundColor != null
                    ? ColorStateList.valueOf(action.badgeBackgroundColor) : null);
            if (action.badgeTextColor != null) {
                badge.setTextColor(action.badgeTextColor);
            } else {
                badge.setTextColor(defaultInk);
            }
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }

        if (mIconLoaded.add(item.addon.id)) {
            Image source = action != null && action.icon != null ? action.icon : item.addon.icon;
            AddonIcons.load(requireContext(), source, mIconPx, d -> {
                if (isAdded()) {
                    icon.setImageDrawable(d);
                }
            });
        }

        String id = item.addon.id;
        if (review) {
            card.setOnClickListener(v -> openReview(id));
        } else if (usable) {
            card.setOnClickListener(v -> {
                // The add-on's own click: it runs its handler, or Gecko asks
                // for its popup (AddonManager), which the browser then shows.
                action.click();
                NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_addon_actions);
            });
        } else {
            card.setOnClickListener(null);
        }
        // After the listener: setOnClickListener makes a view clickable.
        boolean tappable = review || usable;
        card.setEnabled(tappable);
        card.setClickable(tappable);
        card.setAlpha(tappable ? 1f : 0.6f);
    }
}
