package com.solarized.firedown.phone.dialogs;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.core.app.ShareCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.Keys;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;


public class WebOptionSheetDialogFragment extends BaseBottomSheetDialogFragment implements OptionsAdapter.OnItemClickListener {

    /**
     * Host fragments that show this dialog via {@code .show()} (not
     * via the nav graph) can implement {@link Host} to intercept the
     * Open and Edit actions, since the default routing (Activity
     * setResult/finish for Open; navController.navigate for Edit)
     * assumes the dialog was launched from BookmarkActivity. The
     * home pinned-favicons strip is the first such caller.
     */
    public interface Host {
        /** Open the URL in the host fragment's own browser flow. */
        void onWebOptionOpen(@androidx.annotation.NonNull String url);
        /** Open the bookmark edit surface for this id (typically by
         *  launching BookmarkActivity with the BOOKMARK_EDIT intent). */
        void onWebOptionEdit(int id);
    }

    @Nullable private Host mHost;

    private WebBookmarkViewModel mWebBookmarkViewModel;

    private WebHistoryViewModel mWebHistoryViewModel;

    private String mCurrentUrl;

    private int mId;

    private boolean mEdit;
    private boolean mPinned;

    @Override
    public void onAttach(@androidx.annotation.NonNull android.content.Context context) {
        super.onAttach(context);
        // Host lookup — only fires when the dialog was shown via
        // .show(childFragmentManager, ...) by a Fragment that
        // implements Host. NavController launches keep mHost null
        // and fall through to the legacy setResult / navigate
        // paths inside onItemClick.
        if (getParentFragment() instanceof Host parentHost) {
            mHost = parentHost;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null)
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());

        mId = bundle.getInt(Keys.ITEM_ID, 0);

        mCurrentUrl = bundle.getString(Keys.SHARE_URL, null);

        mEdit = bundle.getBoolean(Keys.EDIT, false);

        mPinned = bundle.getBoolean(Keys.PINNED, false);

        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);

        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);

    }

    @SuppressLint("InvalidSetHasFixedSize")
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener
        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        List<OptionItem> optionItemList = buildOptionItems();

        OptionsAdapter optionsAdapter = new OptionsAdapter(optionItemList, this);

        recyclerView.setAdapter(optionsAdapter);
        recyclerView.setHasFixedSize(true);
        return mView;

    }


    public List<OptionItem> buildOptionItems() {
        TypedArray imgs = getResources().obtainTypedArray(
                mEdit ? R.array.web_options_edit_items_icon : R.array.web_options_items_icon);
        String[] labels = getResources().getStringArray(
                mEdit ? R.array.web_options_edit_items : R.array.web_options_items);
        List<OptionItem> optionItemList = new ArrayList<>(imgs.length());
        try {
            for (int i = 0; i < labels.length; i++) {
                int iconResId = imgs.getResourceId(i, R.drawable.ic_draft_24);
                String label = labels[i];
                // Pin-toggle entry: swap the label to 'Unpin from top'
                // when the bookmark is already pinned, so the user sees
                // the action they're about to take, not the state name.
                if (iconResId == R.drawable.ic_push_pin_24 && mPinned) {
                    label = getString(R.string.web_options_unpin);
                }
                optionItemList.add(new OptionItem(label, iconResId));
            }
        } finally {
            imgs.recycle();
        }
        return optionItemList;
    }


    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;
        int id = item.getIconRes();
        if (id == R.drawable.ic_web_24) {
            if (mHost != null) {
                // Home (or any non-BookmarkActivity host) opens the URL
                // through its own browser flow; setResult/finish on the
                // host activity would finish MainActivity here.
                mHost.onWebOptionOpen(mCurrentUrl);
                dismiss();
            } else {
                Intent resultIntent = new Intent(Intent.ACTION_VIEW);
                resultIntent.setData(Uri.parse(mCurrentUrl));
                mActivity.setResult(Activity.RESULT_OK, resultIntent);
                mActivity.finish();
            }
        } else if (id == R.drawable.ic_baseline_delete_24) {
            mWebBookmarkViewModel.delete(mId);
            mWebHistoryViewModel.delete(mId);
            dismissOrPop();
        } else if (id == R.drawable.ic_share_24) {
            new ShareCompat.IntentBuilder(mActivity)
                    .setType("text/plain")
                    .setChooserTitle(getString(R.string.share_url))
                    .setText(mCurrentUrl)
                    .startChooser();
            dismissOrPop();
        } else if(id == R.drawable.ic_edit_24){
            if (mHost != null) {
                // Edit fragment lives in nav_graph_bookmark; from a
                // non-bookmark host the cleanest path is to launch
                // BookmarkActivity with the BOOKMARK_EDIT intent.
                mHost.onWebOptionEdit(mId);
                dismiss();
            } else {
                Bundle bundle = getArguments();
                NavigationUtils.navigateSafe(mNavController, R.id.web_bookmark_edit, bundle);
            }
        } else if(id == R.drawable.ic_push_pin_24){
            // Toggle pin state for this bookmark. The DAO query orders
            // by is_pinned DESC, so the row will visibly jump to the
            // top (or out of the top section) on the next paged
            // refresh.
            mWebBookmarkViewModel.setPinned(mId, !mPinned);
            dismissOrPop();
        }
    }

    /** Dismiss the sheet via NavController if it was navigated to,
     *  via the standard sheet dismiss otherwise. Both ways close
     *  the dialog; the NavController path matches the historical
     *  BookmarkActivity launch. */
    private void dismissOrPop() {
        if (mHost != null) {
            dismiss();
        } else {
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_web_options);
        }
    }
}
