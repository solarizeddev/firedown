package com.solarized.firedown.phone.dialogs;


import android.app.Dialog;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ShareCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.Keys;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.Utils;

import java.util.ArrayList;
import java.util.List;


public class WebOptionSheetDialogFragment extends BaseBottomSheetDialogFragment implements OptionsAdapter.OnItemClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;

    private WebHistoryViewModel mWebHistoryViewModel;

    private BrowserURIViewModel mBrowserURIViewModel;

    private String mCurrentUrl;

    private String mTitle;

    private String mIcon;

    private int mId;

    private boolean mEdit;

    private boolean mIncognito;

    private boolean mArgsMissing;

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if (bundle == null) {
            // Args lost on restore — onCreateDialog dismisses on show.
            mArgsMissing = true;
            return;
        }

        mId = bundle.getInt(Keys.ITEM_ID, 0);

        mCurrentUrl = bundle.getString(Keys.SHARE_URL, null);

        mTitle = bundle.getString(Keys.TITLE, null);

        mIcon = bundle.getString(Keys.ICON, null);

        mEdit = bundle.getBoolean(Keys.EDIT, false);

        mIncognito = bundle.getBoolean(Keys.IS_INCOGNITO, false);

        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);

        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);

        // Activity-scoped so BrowserFragment's OPEN_EXTERNAL_URI observer
        // picks up the event we publish from 'Open in New Tab'.
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);

    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mArgsMissing) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }
        return super.onCreateDialog(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        if (mArgsMissing) return null;

        // get the views and attach the listener
        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        List<OptionItem> optionItemList = buildOptionItems();

        OptionsAdapter optionsAdapter = new OptionsAdapter(optionItemList, this);

        recyclerView.setAdapter(optionsAdapter);
        // No setHasFixedSize: inside the wrap-height bottom sheet the
        // recycler is measured AT_MOST (effectively wrap in the scroll
        // direction — lint InvalidSetHasFixedSize), and the optimization
        // buys nothing on a static list that only ever setAdapter()s.
        return mView;

    }


    public List<OptionItem> buildOptionItems() {
        TypedArray imgs = getResources().obtainTypedArray(
                mEdit ? R.array.web_options_edit_items_icon : R.array.web_options_items_icon);
        String[] labels = getResources().getStringArray(
                mEdit ? R.array.web_options_edit_items : R.array.web_options_items);
        List<OptionItem> optionItemList = new ArrayList<>(imgs.length() + 1);
        try {
            for (int i = 0; i < labels.length; i++) {
                int iconResId = imgs.getResourceId(i, R.drawable.ic_draft_24);
                optionItemList.add(new OptionItem(labels[i], iconResId));
            }
        } finally {
            imgs.recycle();
        }
        // The non-edit set serves the HISTORY row (the edit set is a
        // bookmark's own sheet, where this row would be redundant). A
        // history entry that isn't bookmarked yet offers "Bookmark page"
        // right after Open — the row the Downloads-style ⋮ was asked for
        // (issue #306, item 10). Already-bookmarked URLs don't show it:
        // the row would have to become a delete, and the Bookmarks screen
        // owns that. Inserted here rather than in the arrays so the array
        // pair stays the plain Open / Share / Delete the adapter expects
        // its final (destructive) row to be.
        if (!mEdit && mCurrentUrl != null && !mWebBookmarkViewModel.containsUrl(mCurrentUrl)) {
            optionItemList.add(Math.min(1, optionItemList.size()),
                    new OptionItem(getString(R.string.browser_menu_bookmark_this_page_2),
                            R.drawable.ic_bookmark_border_24));
        }
        return optionItemList;
    }

    /**
     * Bookmarks the sheet's URL from the history row's own facts (title +
     * favicon ride in as args), mirroring {@code WebBookmarkDataRepository
     * .add(GeckoState)}: a blank/about:blank title is stored as null so the
     * list falls back to the URL and the next page load backfills it.
     */
    private void addBookmark() {
        WebBookmarkEntity entity = new WebBookmarkEntity();
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileTitle(UrlStringUtils.isBlankTitle(mTitle) ? null : Utils.capitalize(mTitle));
        entity.setFileUrl(mCurrentUrl);
        entity.setId(WebBookmarkDataRepository.bookmarkIdFor(mCurrentUrl));
        entity.setFileIcon(mIcon);
        mWebBookmarkViewModel.add(entity);
        Snackbar.make(mActivity.getSnackAnchorView(),
                R.string.browser_bookmark_saved_toast, Snackbar.LENGTH_SHORT).show();
    }


    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;
        int id = item.getIconRes();
        if (id == R.drawable.ic_web_24) {
            // 'Open in New Tab' — mirrors IntentHandler.handleExternalUri:
            // a fresh GeckoStateEntity flagged external so BrowserFragment
            // spawns a new tab for it, then OPEN_EXTERNAL_URI on the
            // activity-scoped ViewModel + navigate to browser popping back
            // to home so the bookmark / history surface doesn't linger.
            // Old flow used setResult(ACTION_VIEW) + finish on
            // BookmarkActivity / HistoryActivity, which closed the whole
            // app once those Activities were dropped.
            // GeckoStateEntity(boolean, String) is the (home, uri)
            // constructor — not incognito. We're loading a real URL, so
            // home=false; incognito is set explicitly below.
            GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false, mCurrentUrl);
            geckoStateEntity.setIncognito(mIncognito);
            geckoStateEntity.setExternal(true);
            mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_EXTERNAL_URI);
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(mIncognito ? R.id.home_incognito : R.id.home, false)
                    .build();
            NavigationUtils.navigateSafe(mNavController, R.id.browser, null, navOptions);
        } else if (id == R.drawable.ic_bookmark_border_24) {
            addBookmark();
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_web_options);
        } else if (id == R.drawable.ic_baseline_delete_24) {
            // Delete in the sheet's OWN domain only. The two id spaces never
            // meet (a bookmark id hashes the URL, a history id the URL plus
            // the day), so the old "delete from both" was a no-op on the
            // other table — except that with bookmark sync ON the bookmark
            // delete soft-deletes and fires a sync push for a row that does
            // not exist, once per history row deleted.
            if (mEdit) {
                mWebBookmarkViewModel.delete(mId);
            } else {
                mWebHistoryViewModel.delete(mId);
            }
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_web_options);
        } else if (id == R.drawable.ic_share_24) {
            new ShareCompat.IntentBuilder(mActivity)
                    .setType("text/plain")
                    .setChooserTitle(getString(R.string.share_url))
                    .setText(mCurrentUrl)
                    .startChooser();
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_web_options);
        } else if(id == R.drawable.ic_edit_24){
            Bundle bundle = getArguments();
            NavigationUtils.navigateSafe(mNavController, R.id.web_bookmark_edit, bundle);
        }
    }
}
