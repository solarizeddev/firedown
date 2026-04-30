package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.*;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.*;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.*;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.WebHistoryAdapter;
import com.solarized.firedown.ui.diffs.WebHistoryDiffCallback;
import com.solarized.firedown.utils.NavigationUtils;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebHistoryFragment extends BaseFocusFragment implements SearchView.OnQueryTextListener, OnItemClickListener {

    private static final String TAG = WebHistoryFragment.class.getSimpleName();
    private WebHistoryViewModel mWebHistoryViewModel;
    private WebHistoryAdapter mAdapter;

    /** Set when a new query has been dispatched; consumed on the next successful refresh. */
    private boolean mPendingScrollToTop = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);
        setupBackPress();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_web_history, container, false);
        setupViews(v);
        setupToolbar();
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        postponeEnterTransition();

        final ViewGroup parentView = (ViewGroup) view.getParent();

        mAdapter.addLoadStateListener(loadStates -> {
            if (loadStates.getRefresh() instanceof LoadState.NotLoading) {
                if (mAdapter.getItemCount() == 0) {
                    if (mSearchView != null && !TextUtils.isEmpty(mSearchView.getQuery())) {
                        Log.d(TAG, "SearchView Query: " + mSearchView.getQuery());
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_query);
                    } else {
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_history);
                    }
                    mLCEERecyclerView.showEmpty();
                } else {
                    mLCEERecyclerView.hideAll();
                    if (mPendingScrollToTop && mRecyclerView != null) {
                        mPendingScrollToTop = false;
                        mRecyclerView.scrollToPosition(0);
                    }
                }

                parentView.getViewTreeObserver()
                        .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                parentView.getViewTreeObserver().removeOnPreDrawListener(this);
                                startPostponedEnterTransition();
                                return true;
                            }
                        });
            }
            return null;
        });

        mWebHistoryViewModel.getDispatchedQuery().observe(getViewLifecycleOwner(),
                q -> mPendingScrollToTop = true);
    }

    private void setupBackPress() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "handleBackPressed");
                if (mActionModeEnabled) {
                    stopActionMode();
                } else if (mSearchItem != null && mSearchItem.isActionViewExpanded()) {
                    closeSearchView();
                } else {
                    setEnabled(false);
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void setupViews(View v) {
        mLCEERecyclerView = v.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_history);
        mLCEERecyclerView.setEmptyText(R.string.empty_list_browser_history);
        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        mToolbar = v.findViewById(R.id.toolbar);
        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);
        mAdapter = new WebHistoryAdapter(mActivity, new WebHistoryDiffCallback(), this);
        mRecyclerView.setAdapter(mAdapter);

        mWebHistoryViewModel.getWebHistory().observe(getViewLifecycleOwner(), data -> {
            mAdapter.submitData(getLifecycle(), data);
        });
    }

    private void setupToolbar() {
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(mActionModeEnabled ? R.menu.menu_action : R.menu.menu_web_options, menu);
                if (!mActionModeEnabled) {
                    mSearchItem = menu.findItem(R.id.action_search);
                    mSearchView = (SearchView) mSearchItem.getActionView();
                    if (mSearchView != null) {
                        mSearchView.setOnQueryTextListener(WebHistoryFragment.this);
                    }
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_delete) {
                    handleDeletion();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        mToolbar.setNavigationOnClickListener(v1 -> {
            if (mActionModeEnabled) {
                stopActionMode();
            } else {
                mActivity.finish();
            }
        });
    }

    private void handleDeletion() {
        if (mActionModeEnabled) {
            for (int pos : mAdapter.getSelected()) {
                Object item = mAdapter.peek(pos);
                if (item instanceof WebHistoryEntity entity) {
                    mWebHistoryViewModel.delete(entity);
                }
            }
            stopActionMode();
        } else {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_history);
        }
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        mWebHistoryViewModel.search(newText);
        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mWebHistoryViewModel.search(query);
        return true;
    }

    public void stopActionMode() {
        mActionModeEnabled = false;
        mAdapter.setActionMode(false);
        mAdapter.resetSelected();
        mActivity.invalidateOptionsMenu();
        mToolbar.setTitle(R.string.navigation_web_history);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mAdapter = null;
    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        Object item = mAdapter.peek(position);
        if (!(item instanceof WebHistoryEntity entity)) {
            return;
        }

        if (mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
        } else {
            if (resId == R.id.file_more) {
                mWebHistoryViewModel.delete(entity);
            } else if (resId == R.id.item_web_history) {
                GeckoStateEntity geckoState = new GeckoStateEntity(false);
                geckoState.setUri(entity.getUrl());
                setSessionResult(geckoState);
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        Object item = mAdapter.peek(position);
        if (!(item instanceof WebHistoryEntity))
            return;

        if (!mActionModeEnabled) {
            mActionModeEnabled = true;
            mActivity.invalidateOptionsMenu();
            mAdapter.setActionMode(true);
        }
        mAdapter.setSelected(position);
        setActionModeTitle(mAdapter.getSelectedSize());
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }
}