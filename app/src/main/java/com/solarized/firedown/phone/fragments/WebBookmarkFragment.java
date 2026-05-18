package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.ui.CardViewListItemDecoration;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.WebBookmarkDiffCallback;
import com.solarized.firedown.ui.adapters.WebBookmarkAdapter;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.HashSet;


public class WebBookmarkFragment extends BaseFocusFragment implements OnItemClickListener, SearchView.OnQueryTextListener {

    private static final String TAG = WebBookmarkFragment.class.getName();
    private WebBookmarkAdapter mAdapter;
    private WebBookmarkViewModel mWebBookmarkViewModel;
    private boolean mPendingScrollToTop = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
        mWebBookmarkViewModel.search(null);
        setupBackPress();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_web_bookmark, container, false);

        mWebBookmarkViewModel.getDispatchedQuery().observe(getViewLifecycleOwner(), q -> mPendingScrollToTop = true);

        setupViews(v);
        setupToolbar(v);

        return v;
    }


    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        postponeEnterTransition();

        final ViewGroup parentView = (ViewGroup) view.getParent();
        // Wait for the data to load

        mAdapter.addLoadStateListener(loadStates -> {
            if (loadStates.getRefresh() instanceof LoadState.NotLoading) {
                if (mAdapter.getItemCount() == 0) {
                    if (mSearchView != null && !TextUtils.isEmpty(mSearchView.getQuery())) {
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_query);
                    } else {
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_bookmarks);
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
                            @Override public boolean onPreDraw() {
                                parentView.getViewTreeObserver().removeOnPreDrawListener(this);
                                startPostponedEnterTransition();
                                return true;
                            }
                        });
            }
            return null;
        });


    }

    public void setupViews(View v){
        mLCEERecyclerView = v.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyText(R.string.empty_list_bookmarks);
        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_bookmark);

        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        mRecyclerView.setVerticalScrollBarEnabled(true);
        mRecyclerView.addItemDecoration(new CardViewListItemDecoration(mActivity, R.dimen.list_spacing));
        mAdapter = new WebBookmarkAdapter(mActivity, new WebBookmarkDiffCallback(), this);
        mRecyclerView.setAdapter(mAdapter);

        mWebBookmarkViewModel.getWebBookmark().observe(getViewLifecycleOwner(), mObservableDownloads ->
                mAdapter.submitData(getLifecycle(), mObservableDownloads));
    }

    public void setupToolbar(View v){
        mToolbar = v.findViewById(R.id.toolbar);
        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset),0);
        mToolbar.setNavigationOnClickListener(v1 -> {
            if(mActionModeEnabled){
                stopActionMode();
            }else{
                mActivity.finish();
            }
        });
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if(mActionModeEnabled){
                    menuInflater.inflate(R.menu.menu_action, menu);
                }else{
                    menuInflater.inflate(R.menu.menu_web_options, menu);
                    mSearchItem = menu.findItem(R.id.action_search);
                    mSearchView = (SearchView) mSearchItem.getActionView();
                    if (mSearchView != null) {
                        mSearchView.setOnQueryTextListener(WebBookmarkFragment.this);
                    }
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_delete) {
                    if (mActionModeEnabled) {
                        HashSet<Integer> selected = mAdapter.getSelected();
                        for (int position : selected) {
                            mWebBookmarkViewModel.delete(mAdapter.getWebBookmarkItem(position));
                        }
                        stopActionMode();
                    } else {
                        NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_bookmarks);
                    }
                    return true;
                } else if (id == android.R.id.home) {
                    mActivity.finish();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    public void setupBackPress(){
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "handleBackPressed");
                if(mActionModeEnabled){
                    stopActionMode();
                } else if (mSearchItem != null && mSearchItem.isActionViewExpanded()) {
                    closeSearchView();
                }else {
                    setEnabled(false); //this is important line
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mToolbar = null;
        mAdapter = null;
    }

    @Override
    public void onItemClick(int position, int resId) {

        Log.d(TAG, "onItemClick: " + resId);

        if (position == RecyclerView.NO_POSITION) {
            Log.d(TAG, "onItemClick incorrect position");
            return;
        }

        if(mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
        }else{
            if(resId == R.id.file_more){
                WebBookmarkEntity webBookmarkEntity = mAdapter.snapshot().get(position);
                if(webBookmarkEntity != null){
                    Bundle bundle = new Bundle();
                    bundle.putInt(Keys.ITEM_ID, webBookmarkEntity.getId());
                    bundle.putString(Keys.SHARE_URL, webBookmarkEntity.getUrl());
                    bundle.putString(Keys.TITLE, webBookmarkEntity.getTitle());
                    bundle.putBoolean(Keys.EDIT, true);
                    NavigationUtils.navigateSafe(mNavController,R.id.dialog_web_options, R.id.web_bookmark, bundle);
                }
            }else if(resId == R.id.item_web_bookmark){
                WebBookmarkEntity webBookmarkEntity = mAdapter.snapshot().get(position);
                if(webBookmarkEntity != null){
                    GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false);
                    geckoStateEntity.setUri(webBookmarkEntity.getUrl());
                    setSessionResult(geckoStateEntity);
                }
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        if(mActionModeEnabled) {
            setActionModeTitle(mAdapter.getSelectedSize());
            return;
        }
        mActionModeEnabled = true;
        mToolbar.invalidateMenu();
        mAdapter.setActionMode(true);
        mAdapter.setSelected(position);
        setActionModeTitle(mAdapter.getSelectedSize());
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }


    @Override
    public boolean onQueryTextSubmit(String query) {
        mWebBookmarkViewModel.search(query);
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        mWebBookmarkViewModel.search(newText);
        return false;
    }


    private void stopActionMode(){
        mAdapter.resetSelected();
        mAdapter.setActionMode(false);
        mActionModeEnabled = false;
        mToolbar.invalidateMenu();
        mToolbar.setTitle(getString(R.string.navigation_web_bookmarks));
    }



}


