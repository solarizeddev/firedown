package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
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

/**
 * Dual-purpose: edits an existing pinned shortcut (entity passed in
 * via bundle) and, when the bundled entity has {@code id == 0} or no
 * entity is passed at all, creates a brand-new shortcut from a blank
 * name + URL form. The 'add' path is reached from the home empty
 * state's 'Add a shortcut' CTA; the 'edit' path from the long-press
 * options sheet on an existing tile.
 */
public class ShortCutsEditDialogFragment extends BaseDialogFragment {

    private static final String URL_REGEX = "^(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]$";
    private static final String SCHEME_PREFIX = "(?i)^https?://.*";
    private static final String DEFAULT_SCHEME = "https://";

    private ShortCutsViewModel mShortCutsViewModel;

    @Nullable private ShortCutsEntity mShortCutsEntity;
    private boolean mAddMode;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mShortCutsViewModel = new ViewModelProvider(this).get(ShortCutsViewModel.class);

        Bundle bundle = getArguments();
        if (bundle != null) {
            mShortCutsEntity = bundle.getParcelable(Keys.ITEM_ID);
        }

        // Add mode = no entity in the bundle, or one with an unset id
        // (default constructor leaves it at 0). Edit mode = real
        // persisted entity with a non-zero id.
        mAddMode = mShortCutsEntity == null || mShortCutsEntity.getId() == 0;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme();

        String name = mAddMode ? "" : (mShortCutsEntity != null ? mShortCutsEntity.getTitle() : "");
        String url  = mAddMode ? "" : (mShortCutsEntity != null ? mShortCutsEntity.getUrl()   : "");

        View view = LayoutInflater.from(mActivity).inflate(R.layout.fragment_dialog_shortcuts_edit, null);

        EditText nameInput = view.findViewById(R.id.top_site_title);
        TextInputLayout urlLayout = view.findViewById(R.id.top_site_url_layout);
        TextInputEditText urlInput = view.findViewById(R.id.top_site_url);

        nameInput.setText(name);
        urlInput.setText(url);

        int titleRes = mAddMode
                ? R.string.top_sites_add_dialog_title
                : R.string.top_sites_edit_dialog_title;

        AlertDialog alertDialog = new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(R.string.top_sites_edit_dialog_save, (d, which) -> {
                    saveData(nameInput.getText(), urlInput.getText());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        alertDialog.setOnShowListener(dialog -> {
            Button saveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);

            // Initial check in case the starting URL is invalid
            saveButton.setEnabled(isFormValid(nameInput.getText(), urlInput.getText(), urlLayout));

            TextWatcher watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    saveButton.setEnabled(isFormValid(nameInput.getText(), urlInput.getText(), urlLayout));
                }

                @Override
                public void afterTextChanged(Editable s) {}
            };

            urlInput.addTextChangedListener(watcher);
            nameInput.addTextChangedListener(watcher);
        });


        return alertDialog;
    }


    private boolean isFormValid(@Nullable CharSequence nameInput,
                                @Nullable CharSequence urlInput,
                                TextInputLayout urlLayout) {
        boolean nameOk = nameInput != null && !TextUtils.isEmpty(nameInput.toString().trim());
        return nameOk && validateUrl(urlInput, urlLayout);
    }

    /**
     * Validates the URL and updates the UI error state. Accepts both
     * scheme-less inputs (e.g. {@code youtube.com}) and explicit
     * schemes ({@code https://…}, {@code http://…}); a missing
     * scheme is treated as if {@code https://} were prepended for
     * the purpose of validation, and {@link #normalizeUrl(String)}
     * does the same prepend at save time so the stored URL is always
     * scheme-qualified.
     */
    private boolean validateUrl(@Nullable CharSequence input, TextInputLayout layout) {
        // Convert null to empty string for consistent regex checking
        String text = (input == null) ? "" : input.toString().trim();

        if (text.isEmpty()) {
            layout.setError(null);
            return false;
        }

        String candidate = text.matches(SCHEME_PREFIX) ? text : DEFAULT_SCHEME + text;

        if (candidate.matches(URL_REGEX)) {
            layout.setError(null);
            return true;
        } else {
            layout.setError(getString(R.string.settings_doh_server_error_format));
            return false;
        }
    }

    /**
     * Returns {@code raw} prefixed with {@code https://} if it doesn't
     * already start with a scheme. Mirrors what Chrome and Firefox do
     * in their bookmark / shortcut dialogs — typing a bare domain is
     * the common case and forcing the user to type the scheme is
     * needless friction. Explicit {@code http://} is preserved.
     */
    private static String normalizeUrl(@NonNull String raw) {
        return raw.matches(SCHEME_PREFIX) ? raw : DEFAULT_SCHEME + raw;
    }


    private void saveData(@Nullable Editable nameEditable, @Nullable Editable urlEditable) {
        // Safely convert to string and trim
        String updatedName = (nameEditable != null) ? nameEditable.toString().trim() : "";
        String updatedUrl = (urlEditable != null) ? urlEditable.toString().trim() : "";

        if (updatedUrl.isEmpty() || updatedName.isEmpty())
            return;

        updatedUrl = normalizeUrl(updatedUrl);

        if (mAddMode) {
            ShortCutsEntity entity = new ShortCutsEntity();
            entity.setFileUrl(updatedUrl);
            entity.setFileTitle(updatedName);
            entity.setFileDomain(WebUtils.getDomainName(updatedUrl));
            entity.setFileDate(System.currentTimeMillis());
            entity.setId(updatedUrl.hashCode());
            mShortCutsViewModel.add(entity);
        } else {
            ShortCutsEntity updatedShortcut = new ShortCutsEntity(mShortCutsEntity);
            updatedShortcut.setFileUrl(updatedUrl);
            updatedShortcut.setFileTitle(updatedName);
            updatedShortcut.setFileDomain(WebUtils.getDomainName(updatedUrl));
            updatedShortcut.setFileIconResolution(0);
            updatedShortcut.setFileIcon(null);
            mShortCutsViewModel.update(updatedShortcut);
        }

        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_shortcuts_edit);
    }


}
