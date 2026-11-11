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
                // This runs inside closePopup's setValue, before the session
                // closes: the view lets go of it first.
                if (mGeckoView != null) {
                    mGeckoView.releaseSession();
                }
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

    /** Fragment result: the user left a popup opened from the shield sheet. */
    public static final String RESULT_BACK_TO_SHIELD = "addon_popup_back_to_shield";

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        // Back or a tap outside, never the add-on closing its own popup
        // (that is a pop, not a cancel): return to the sheet it came from.
        if (mPopup != null && mPopup.fromShield) {
            getParentFragmentManager().setFragmentResult(RESULT_BACK_TO_SHIELD, new Bundle());
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        Activity activity = getActivity();
        boolean changing = activity != null && activity.isChangingConfigurations();
        if (mPopup != null && !changing) {
            // The view lets go of the session before it closes (a closed
            // session must never stay attached to a GeckoView).
            if (mGeckoView != null) {
                mGeckoView.releaseSession();
            }
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
