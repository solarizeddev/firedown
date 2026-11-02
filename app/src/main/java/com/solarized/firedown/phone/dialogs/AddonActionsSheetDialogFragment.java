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
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.SheetGroups;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.List;

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
    }

    private void render() {
        if (mList == null || !isAdded()) {
            return;
        }
        GeckoSession session = mGeckoState == null ? null : mGeckoState.getGeckoSession();
        List<AddonManager.ActionItem> items = mAddonManager.actionsFor(session, mIsIncognito);
        mList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (AddonManager.ActionItem item : items) {
            mList.addView(row(inflater, item));
        }
        mList.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        SheetGroups.applyCorners(mList);
        SheetGroups.applyCorners(mView.findViewById(R.id.addon_actions_manage_group));
    }

    @NonNull
    private View row(@NonNull LayoutInflater inflater, @NonNull AddonManager.ActionItem item) {
        MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.item_addon_action, mList, false);
        ImageView icon = card.findViewById(R.id.addon_action_icon);
        TextView title = card.findViewById(R.id.addon_action_title);
        TextView subtitle = card.findViewById(R.id.addon_action_subtitle);
        TextView badge = card.findViewById(R.id.addon_action_badge);

        WebExtension.Action action = item.action;
        String name = item.addon.name;
        String label = action != null && !TextUtils.isEmpty(action.title) ? action.title : name;
        title.setText(label);

        String sub = null;
        boolean usable = action != null && !Boolean.FALSE.equals(action.enabled);
        if (action == null) {
            sub = getString(R.string.addons_no_button);
        } else if (!usable) {
            sub = getString(R.string.addons_not_here);
        } else if (!label.equals(name)) {
            sub = name;
        }
        if (sub != null) {
            subtitle.setText(sub);
            subtitle.setVisibility(View.VISIBLE);
        }

        if (action != null && !TextUtils.isEmpty(action.badgeText)) {
            badge.setText(action.badgeText);
            if (action.badgeBackgroundColor != null) {
                badge.setBackgroundTintList(ColorStateList.valueOf(action.badgeBackgroundColor));
            }
            if (action.badgeTextColor != null) {
                badge.setTextColor(action.badgeTextColor);
            }
            badge.setVisibility(View.VISIBLE);
        }

        if (action != null && action.icon != null) {
            AddonIcons.load(requireContext(), action.icon, mIconPx, d -> {
                if (isAdded()) {
                    icon.setImageDrawable(d);
                }
            });
        } else {
            AddonIcons.load(requireContext(), item.addon.icon, mIconPx, d -> {
                if (isAdded()) {
                    icon.setImageDrawable(d);
                }
            });
        }

        card.setEnabled(usable);
        card.setClickable(usable);
        card.setAlpha(usable ? 1f : 0.6f);
        if (usable) {
            card.setOnClickListener(v -> {
                // The add-on's own click: it runs its handler, or Gecko asks
                // for its popup (AddonManager), which the browser shows.
                action.click();
                NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_addon_actions);
            });
        }
        return card;
    }
}
