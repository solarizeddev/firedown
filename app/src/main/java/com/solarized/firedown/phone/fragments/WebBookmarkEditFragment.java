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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.models.WebBookmarkViewModel;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebBookmarkEditFragment extends BaseFocusFragment implements View.OnClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;

    private MaterialButton mSaveButton;
    private View mDeleteButton;
    private TextInputEditText mHostnameInput;
    private TextInputEditText mTitleNameInput;

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
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_web_bookmark_edit, container, false);

        mDeleteButton = v.findViewById(R.id.delete_button);
        mSaveButton = v.findViewById(R.id.save_button);
        mHostnameInput = v.findViewById(R.id.host_field);
        mTitleNameInput = v.findViewById(R.id.title_field);

        mSaveButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);

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
            }
        };

        mTitleNameInput.addTextChangedListener(watcher);
        mHostnameInput.addTextChangedListener(watcher);
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
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mTitleNameInput = null;
        mHostnameInput = null;
        mSaveButton = null;
        mDeleteButton = null;
    }
}
