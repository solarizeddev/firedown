package com.solarized.firedown.phone.fragments;

import android.os.Bundle;

import androidx.lifecycle.LiveData;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.IncognitoColors;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Tab switcher for incognito (private browsing) tabs.
 *
 * <p>Key differences from regular tabs:</p>
 * <ul>
 *   <li>No tab archiving — closed incognito tabs are discarded immediately</li>
 *   <li>No undo snackbar — sessions are closed on tab removal</li>
 *   <li>No persistence — tab list is lost on app kill</li>
 *   <li>Auto-switches to regular tabs page when last incognito tab is closed</li>
 * </ul>
 *
 * <p>Lives inside {@link TabsHolderFragment}'s ViewPager2.</p>
 */
@AndroidEntryPoint
public class TabsIncognitoFragment extends BaseTabsFragment {

    // ── Abstract implementations ─────────────────────────────────────

    @Override
    protected LiveData<List<GeckoStateEntity>> getTabsLiveData() {
        return mIncognitoStateViewModel.getTabs();
    }

    @Override
    protected GeckoState getGeckoState(int tabId) {
        return mIncognitoStateViewModel.getGeckoState(tabId);
    }

    @Override
    protected void activateGeckoState(GeckoState geckoState) {
        mIncognitoStateViewModel.setGeckoState(geckoState, true);
    }

    @Override
    protected void removeGeckoState(GeckoState geckoState) {
        mIncognitoStateViewModel.closeGeckoState(geckoState);
    }

    @Override
    protected int getEmptyTextRes() {
        return R.string.tabs_incognito_empty;
    }

    @Override
    protected void onTabSelected(GeckoStateEntity entity, GeckoState geckoState) {
        if (entity.isHome()) {
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SELECT_INCOGNITO_HOME, Bundle.EMPTY);
        } else {
            mBrowserURIViewModel.onEventSelected(entity, IntentActions.OPEN_SESSION);
            Bundle args = new Bundle();
            args.putBoolean("incognito", true);
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SELECT_BROWSER, args);
        }
    }

    @Override
    protected void onTabClosed(GeckoStateEntity entity, GeckoState geckoState) {
        // No undo — close session immediately
        geckoState.closeGeckoSession();

        // When the last incognito tab closes, navigate away from the
        // (now empty) incognito tabs page. The holder fragment handles
        // the target — if regular is empty too, it can route to regular
        // home.
        if (isAdded() && mIncognitoStateViewModel.isEmpty()) {
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SWITCH_TO_REGULAR, Bundle.EMPTY);
        }
    }


    // ── Incognito-specific UI ───────────────────────────────────────

    public void applyIncognitoColors(boolean incognito) {
        if (mLCEERecyclerView == null) return;

        int onSurface = IncognitoColors.getOnSurface(mActivity, incognito);

        mLCEERecyclerView.setEmptyTextColor(onSurface);
        mLCEERecyclerView.setEmptySubTextColor(
                IncognitoColors.getOnSurfaceVariant(mActivity, incognito));
    }
}