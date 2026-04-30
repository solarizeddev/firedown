package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.models.ShortCutsViewModel;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.WebUtils;

public class ShortCutsEditDialogFragment extends BaseDialogFragment {

    private static final String URL_REGEX = "^(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]$";

    private ShortCutsViewModel mShortCutsViewModel;

    private ShortCutsEntity mShortCutsEntity;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null){
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());
        }

        mShortCutsViewModel = new ViewModelProvider(this).get(ShortCutsViewModel.class);

        mShortCutsEntity = bundle.getParcelable(Keys.ITEM_ID);

        assert mShortCutsEntity != null;

    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        String name = mShortCutsEntity.getTitle();
        String url = mShortCutsEntity.getUrl();

        View view = LayoutInflater.from(mActivity).inflate(R.layout.fragment_dialog_shortcuts_edit, null);

        EditText nameInput = view.findViewById(R.id.top_site_title);
        TextInputLayout urlLayout = view.findViewById(R.id.top_site_url_layout);
        TextInputEditText urlInput = view.findViewById(R.id.top_site_url);

        nameInput.setText(name);
        urlInput.setText(url);

        AlertDialog alertDialog = new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(R.string.top_sites_edit_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.top_sites_edit_dialog_save, (d, which) -> {
                    saveData(nameInput.getText(), urlInput.getText());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        alertDialog.setOnShowListener(dialog -> {
            Button saveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);

            // Initial check in case the starting URL is invalid
            saveButton.setEnabled(validateUrl(urlInput.getText(), urlLayout));

            urlInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    boolean isValid = validateUrl(urlInput.getText(), urlLayout);
                    saveButton.setEnabled(isValid);
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        });


        return alertDialog;
    }


    /**
     * Validates the URL and updates the UI error state.
     */
    private boolean validateUrl(@Nullable CharSequence input, TextInputLayout layout) {
        // Convert null to empty string for consistent regex checking
        String text = (input == null) ? "" : input.toString();

        if (text.isEmpty()) {
            layout.setError(null);
            return false;
        }

        if (text.matches(URL_REGEX)) {
            layout.setError(null);
            return true;
        } else {
            layout.setError(getString(R.string.settings_doh_server_error_format));
            return false;
        }
    }


    private void saveData(@Nullable Editable nameEditable, @Nullable Editable urlEditable) {
        // Safely convert to string and trim
        String updatedName = (nameEditable != null) ? nameEditable.toString().trim() : "";
        String updatedUrl = (urlEditable != null) ? urlEditable.toString().trim() : "";

        if (updatedUrl.isEmpty() || updatedName.isEmpty())
            return;

        ShortCutsEntity updatedShortcut = new ShortCutsEntity(mShortCutsEntity);

        updatedShortcut.setFileUrl(updatedUrl);
        updatedShortcut.setFileTitle(updatedName);
        updatedShortcut.setFileDomain(WebUtils.getDomainName(updatedUrl));
        updatedShortcut.setFileIconResolution(0);
        updatedShortcut.setFileIcon(null);
        updatedShortcut.setFileIconResolution(0);


        mShortCutsViewModel.update(updatedShortcut);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_shortcuts_edit);
    }


}
