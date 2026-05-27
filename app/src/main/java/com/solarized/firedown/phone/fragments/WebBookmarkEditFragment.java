package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.ContextThemeWrapper;
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
import androidx.navigation.NavOptions;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.WebUtils;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebBookmarkEditFragment extends BaseFocusFragment implements View.OnClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;

    private MaterialButton mSaveButton;
    private View mDeleteButton;
    private TextInputLayout mHostLayout;
    private TextInputEditText mHostnameInput;
    private TextInputEditText mTitleNameInput;
    private TextView mTitlePreview;
    private TextView mUrlPreview;
    private AppCompatImageView mFaviconView;

    private RequestOptions mFaviconRequestOptions;

    private WebBookmarkEntity mWebBookmarkEntity;
    private int mId;
    private int mPreviousId;
    private boolean mIncognito;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        if (bundle != null) {
            mId = bundle.getInt(Keys.ITEM_ID);
            mIncognito = bundle.getBoolean(Keys.IS_INCOGNITO, false);
        }
        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Mirror WebBookmarkFragment: overlay Material colour tokens
        // with incognito variants when entered from an incognito
        // context so the form chrome matches the browser we came from.
        if (mIncognito) {
            inflater = inflater.cloneInContext(new ContextThemeWrapper(
                    inflater.getContext(), R.style.ThemeOverlay_FireDown_Incognito));
        }
        View v = inflater.inflate(R.layout.fragment_web_bookmark_edit, container, false);

        // Sync window decor + system bars to the mode we were opened
        // in.
        applyWindowIncognitoTheme(mIncognito);

        mDeleteButton = v.findViewById(R.id.delete_button);
        mSaveButton = v.findViewById(R.id.save_button);
        mHostLayout = v.findViewById(R.id.host_text_input_layout);
        mHostnameInput = v.findViewById(R.id.host_field);
        mTitleNameInput = v.findViewById(R.id.title_field);
        mTitlePreview = v.findViewById(R.id.edit_title_preview);
        mUrlPreview = v.findViewById(R.id.edit_url_preview);
        mFaviconView = v.findViewById(R.id.edit_favicon);

        mSaveButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);

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
                // Cache the loaded strings BEFORE any setText. The text
                // watcher fires on each setText and writes back into
                // mWebBookmarkEntity (== result), which means the title
                // setText mutates result.getUrl() to "https://" while
                // the host field is still empty — and the next line
                // would then pick that mutated value up instead of the
                // original. Holding the snapshot in locals avoids that.
                String loadedTitle = result.getTitle();
                String loadedUrl = result.getUrl();
                mTitleNameInput.setText(loadedTitle);
                mHostnameInput.setText(loadedUrl);
                updatePreview(loadedTitle, loadedUrl);
                // Same Glide call shape the bookmark list adapter uses
                // — falls back to a domain-derived placeholder when the
                // entity's icon column is empty.
                GlideHelper.load(result.getIcon(), loadedUrl,
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
                String rawUrl = mHostnameInput.getText().toString();

                boolean urlEmpty = TextUtils.isEmpty(rawUrl);
                boolean urlValid = !urlEmpty
                        && Patterns.WEB_URL.matcher(rawUrl).matches();

                // Inline error only when the user has typed something
                // invalid — leaving the field empty shouldn't badge it,
                // Save being disabled already communicates "incomplete".
                if (mHostLayout != null) {
                    mHostLayout.setError(!urlEmpty && !urlValid
                            ? getString(R.string.bookmark_url_invalid)
                            : null);
                }

                mSaveButton.setEnabled(!TextUtils.isEmpty(title) && urlValid);

                mWebBookmarkEntity.setFileTitle(title);
                // Only fold the URL into the entity once it parses, so
                // the persisted id (= url.hashCode) doesn't churn off
                // invalid keystrokes like "ahdhdhdh" → "https://ahdhdhdh"
                // and end up saving garbage if the button somehow fires
                // before this watcher catches up.
                if (urlValid) {
                    String normalized = rawUrl.startsWith("http")
                            ? rawUrl : "https://" + rawUrl;
                    mWebBookmarkEntity.setFileUrl(normalized);
                    // Route the id through the repository's canonical
                    // hash so the saved bookmark matches what the
                    // browser popup looks up when the user later visits
                    // this URL — keeps trailing-slash and case variants
                    // pointing to the same row.
                    mWebBookmarkEntity.setId(
                            WebBookmarkDataRepository.bookmarkIdFor(normalized));
                    updatePreview(title, normalized);
                } else {
                    updatePreview(title, rawUrl);
                }
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
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_web_bookmark_edit, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == android.R.id.home) {
                    handleBack();
                    return true;
                }
                if (itemId == R.id.menu_open_in_browser) {
                    openInBrowser();
                    return true;
                }
                if (itemId == R.id.menu_share) {
                    shareBookmarkUrl();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    /**
     * Mirror of the bookmark list's "tap to open" flow: publish an
     * OPEN_URI event on the shared ViewModel, then navigate to the
     * browser via the action that pops back to home so the edit
     * surface isn't left on the back stack.
     */
    private void openInBrowser() {
        if (mWebBookmarkEntity == null) return;
        // GeckoStateEntity(boolean) is the home-flag constructor —
        // not incognito. We're loading a real URL, so home=false;
        // incognito is set explicitly below.
        GeckoStateEntity entity = new GeckoStateEntity(false);
        entity.setIncognito(mIncognito);
        entity.setUri(mWebBookmarkEntity.getUrl());
        mBrowserURIViewModel.onEventSelected(entity, IntentActions.OPEN_URI);
        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(mIncognito ? R.id.home_incognito : R.id.home, false)
                .build();
        NavigationUtils.navigateSafe(mNavController, R.id.browser, null, navOptions);
    }

    private void shareBookmarkUrl() {
        if (mWebBookmarkEntity == null) return;
        new ShareCompat.IntentBuilder(mActivity)
                .setType("text/plain")
                .setChooserTitle(R.string.share_url)
                .setText(mWebBookmarkEntity.getUrl())
                .startChooser();
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
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mTitleNameInput = null;
        mHostnameInput = null;
        mHostLayout = null;
        mSaveButton = null;
        mDeleteButton = null;
        mTitlePreview = null;
        mUrlPreview = null;
        mFaviconView = null;
    }
}
