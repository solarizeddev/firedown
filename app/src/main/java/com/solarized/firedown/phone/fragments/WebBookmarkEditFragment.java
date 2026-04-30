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
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.models.WebBookmarkViewModel;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebBookmarkEditFragment extends BaseFocusFragment implements View.OnClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;

    private MaterialButton mEditSaveButton;
    private MaterialButton mDeleteButton;
    private TextInputLayout mTitleLayout;
    private TextInputLayout mHostLayout;
    private TextInputEditText mHostnameInput;
    private TextInputEditText mTitleNameInput;

    private WebBookmarkEntity mWebBookmarkEntity;
    private int mId;
    private int mPreviousId;
    private boolean mEditEnabled = false;

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
        mEditSaveButton = v.findViewById(R.id.save_button);
        mHostLayout = v.findViewById(R.id.host_text_input_layout);
        mTitleLayout = v.findViewById(R.id.title_text_input_layout);
        mHostnameInput = v.findViewById(R.id.host_field);
        mTitleNameInput = v.findViewById(R.id.title_field);

        mEditSaveButton.setOnClickListener(this);
        mDeleteButton.setOnClickListener(this);

        setupTextWatchers();
        setupToolbar(v);

        // Using the DataCallback approach
        loadBookmarkData();

        return v;
    }

    private void loadBookmarkData() {
        // The ViewModel/Repository handles the Disk-to-Main thread hop
        mWebBookmarkViewModel.getId(mId, result -> {
            if (result != null) {
                mWebBookmarkEntity = result;
                mPreviousId = result.getId();
                mTitleNameInput.setText(result.getTitle());
                mHostnameInput.setText(result.getUrl());
            }
        });
    }

    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (!mEditEnabled || mWebBookmarkEntity == null) return;

                String title = mTitleNameInput.getText().toString();
                String url = mHostnameInput.getText().toString();

                mEditSaveButton.setEnabled(!TextUtils.isEmpty(title) && !TextUtils.isEmpty(url));

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
        NavBackStackEntry prev = mNavController.getPreviousBackStackEntry();
        if (prev != null && prev.getDestination().getId() == R.id.web_bookmark) {
            mNavController.popBackStack();
        } else {
            mActivity.finish();
        }
    }

    @Override
    public void onClick(View v) {
        int viewId = v.getId();
        if (viewId == R.id.save_button) {
            if (mEditEnabled) {
                if (mPreviousId != mWebBookmarkEntity.getId()) {
                    mWebBookmarkViewModel.delete(mPreviousId);
                }
                mWebBookmarkViewModel.add(mWebBookmarkEntity);
                mActivity.finish();
            } else {
                enableEditing();
            }
        } else if (viewId == R.id.delete_button) {
            mWebBookmarkViewModel.delete(mWebBookmarkEntity);
            mActivity.finish();
        }
    }

    private void enableEditing() {
        mEditEnabled = true;
        mEditSaveButton.setText(R.string.prompt_save_confirmation);
        mTitleNameInput.setEnabled(true);
        mHostnameInput.setEnabled(true);
        mHostLayout.setEnabled(true);
        mTitleLayout.setEnabled(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mTitleNameInput = null;
        mHostnameInput = null;
        mEditSaveButton = null;
        mDeleteButton = null;
    }
}