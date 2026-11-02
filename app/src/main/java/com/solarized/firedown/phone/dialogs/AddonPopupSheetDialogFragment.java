package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.utils.NavigationUtils;

import org.mozilla.geckoview.GeckoView;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * An add-on's popup (its browser or page action's page), in a sheet over
 * the browser instead of a window by the toolbar. The session is
 * {@link AddonManager}'s: closing the sheet closes it, and the popup
 * closing itself (window.close, a link opened in a tab) closes the sheet.
 * The sheet doesn't drag: a drag inside belongs to the popup's page.
 */
@AndroidEntryPoint
public class AddonPopupSheetDialogFragment extends BaseBottomSheetDialogFragment {

    @Inject
    AddonManager mAddonManager;

    @Nullable
    private AddonManager.Popup mPopup;
    @Nullable
    private GeckoView mGeckoView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_addon_popup, container, false);
        mPopup = mAddonManager.getPopup().getValue();
        if (mPopup == null) {
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_addon_popup);
            return mView;
        }
        TextView title = mView.findViewById(R.id.addon_popup_title);
        title.setText(mPopup.name);
        mGeckoView = mView.findViewById(R.id.addon_popup_view);
        mGeckoView.setSession(mPopup.session);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAddonManager.getPopup().observe(getViewLifecycleOwner(), popup -> {
            if (popup != mPopup) {
                // Closed by the add-on, or replaced by another add-on's popup.
                NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_addon_popup);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mView != null && mView.getParent() != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) mView.getParent());
            behavior.setDraggable(false);
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        Activity activity = getActivity();
        boolean changing = activity != null && activity.isChangingConfigurations();
        if (mPopup != null && !changing) {
            mAddonManager.closePopup(mPopup);
        }
    }

    @Override
    public void onDestroyView() {
        if (mGeckoView != null) {
            mGeckoView.releaseSession();
            mGeckoView = null;
        }
        super.onDestroyView();
    }
}
