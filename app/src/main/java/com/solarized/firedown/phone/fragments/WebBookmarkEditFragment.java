package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.app.ShareCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.WebUtils;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebBookmarkEditFragment extends BaseFocusFragment implements View.OnClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;

    private MaterialButton mSaveButton;
    private View mDeleteButton;
    private View mOpenInBrowserRow;
    private View mShareRow;
    private TextInputEditText mHostnameInput;
    private TextInputEditText mTitleNameInput;
    private TextView mTitlePreview;
    private TextView mUrlPreview;
    private AppCompatImageView mFaviconView;

    private RequestOptions mFaviconRequestOptions;

    private WebBookmarkEntity mWebBookmarkEntity;
    private int mId;
    private int mPreviousId;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        if (bundle != null) {
            mId = bundle.getInt(Keys.ITEM_ID);
        }
        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_web_bookmark_edit, container, false);

        mDeleteButton = v.findViewById(R.id.delete_button);
        mSaveButton = v.findViewById(R.id.save_button);
        mOpenInBrowserRow = v.findViewById(R.id.open_in_browser_row);
        mShareRow = v.findViewById(R.id.share_row);
        mHostnameInput = v.findViewById(R.id.host_field);
        mTitleNameInput = v.findViewById(R.id.title_field);
        mTitlePreview = v.findViewById(R.id.edit_title_preview);
        mUrlPreview = v.findViewById(R.id.edit_url_preview);
        mFaviconView = v.findViewById(R.id.edit_favicon);

        mSaveButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);
        mOpenInBrowserRow.setOnClickListener(this);
        mShareRow.setOnClickListener(this);

        // Rounded-corner transform so the favicon visually sits inside
        // the chip background rather than poking through its corners.
        // 8dp ≈ chip 10dp minus the 6dp padding on the image view.
        int radius = Math.round(8 * getResources().getDisplayMetrics().density);
        mFaviconRequestOptions = RequestOptions.bitmapTransform(new RoundedCorners(radius));

        // Disabled until loadBookmarkData has populated both fields.
        // The text watcher takes over after that and re-evaluates the
        // empty-state on every keystroke.
        mSaveButton.setEnabled(false);

        setupTextWatchers();
        setupToolbar(v);
        loadBookmarkData();

        return v;
    }

    private void loadBookmarkData() {
        // ViewModel/repository hops disk → main on its own.
        mWebBookmarkViewModel.getId(mId, result -> {
            if (result != null) {
                mWebBookmarkEntity = result;
                mPreviousId = result.getId();
                mTitleNameInput.setText(result.getTitle());
                mHostnameInput.setText(result.getUrl());
                updatePreview(result.getTitle(), result.getUrl());
                // Same Glide call shape the bookmark list adapter uses
                // — falls back to a domain-derived placeholder when the
                // entity's icon column is empty.
                GlideHelper.load(result.getIcon(), result.getUrl(),
                        mFaviconView, mFaviconRequestOptions);
                // Both fields populated → save is meaningful. The text
                // watcher will keep the state in sync as the user edits.
                mSaveButton.setEnabled(true);
            }
        });
    }

    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (mWebBookmarkEntity == null) return;

                String title = mTitleNameInput.getText().toString();
                String url = mHostnameInput.getText().toString();

                mSaveButton.setEnabled(!TextUtils.isEmpty(title) && !TextUtils.isEmpty(url));

                mWebBookmarkEntity.setFileTitle(title);
                if (!url.startsWith("http")) url = "https://" + url;
                mWebBookmarkEntity.setFileUrl(url);
                mWebBookmarkEntity.setId(url.hashCode());

                updatePreview(title, url);
            }
        };

        mTitleNameInput.addTextChangedListener(watcher);
        mHostnameInput.addTextChangedListener(watcher);
    }

    /**
     * Repaints the identity-header title and URL strings. The URL is
     * collapsed to its domain so a long path doesn't push the chip
     * away from the favicon on narrow phones.
     */
    private void updatePreview(@Nullable String title, @Nullable String url) {
        if (mTitlePreview != null) {
            mTitlePreview.setText(TextUtils.isEmpty(title)
                    ? getString(R.string.bookmark_name) : title);
        }
        if (mUrlPreview != null) {
            String domain = TextUtils.isEmpty(url) ? "" : WebUtils.getDomainName(url);
            mUrlPreview.setText(TextUtils.isEmpty(domain) ? url : domain);
        }
    }

    private void setupToolbar(View v) {
        mToolbar = v.findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(v1 -> handleBack());
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {}
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == android.R.id.home) {
                    handleBack();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void handleBack() {
        // Pop back to whichever destination launched us — could be the
        // bookmark list (entered from a bookmark item's more menu) or
        // the browser (entered from the popup 'Edit bookmark' action).
        mNavController.popBackStack();
    }

    @Override
    public void onClick(View v) {
        int viewId = v.getId();
        if (viewId == R.id.save_button) {
            if (mWebBookmarkEntity == null) return;
            // URL hash changes when the URL field changes, so the old
            // entry needs explicit cleanup before the new one lands.
            if (mPreviousId != mWebBookmarkEntity.getId()) {
                mWebBookmarkViewModel.delete(mPreviousId);
            }
            mWebBookmarkViewModel.add(mWebBookmarkEntity);
            mNavController.popBackStack();
        } else if (viewId == R.id.delete_button) {
            if (mWebBookmarkEntity != null) {
                mWebBookmarkViewModel.delete(mWebBookmarkEntity);
            }
            mNavController.popBackStack();
        } else if (viewId == R.id.open_in_browser_row) {
            if (mWebBookmarkEntity == null) return;
            // Mirror the bookmark list's "tap to open" flow: publish an
            // OPEN_URI event on the shared ViewModel, then navigate to
            // browser via the action that pops back to home so the
            // edit surface isn't left on the back stack.
            GeckoStateEntity entity = new GeckoStateEntity(false);
            entity.setUri(mWebBookmarkEntity.getUrl());
            mBrowserURIViewModel.onEventSelected(entity, IntentActions.OPEN_URI);
            NavigationUtils.navigateSafe(mNavController,
                    R.id.action_web_bookmark_edit_to_browser);
        } else if (viewId == R.id.share_row) {
            if (mWebBookmarkEntity == null) return;
            new ShareCompat.IntentBuilder(mActivity)
                    .setType("text/plain")
                    .setChooserTitle(R.string.share_url)
                    .setText(mWebBookmarkEntity.getUrl())
                    .startChooser();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mTitleNameInput = null;
        mHostnameInput = null;
        mSaveButton = null;
        mDeleteButton = null;
        mOpenInBrowserRow = null;
        mShareRow = null;
        mTitlePreview = null;
        mUrlPreview = null;
        mFaviconView = null;
    }
}
