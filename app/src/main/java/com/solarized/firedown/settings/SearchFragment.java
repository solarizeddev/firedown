package com.solarized.firedown.settings;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.repository.SearchRepository;
import com.solarized.firedown.settings.ui.RadioButtonPreference;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class SearchFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = SearchFragment.class.getName();

    /** Sits between the last built-in (XML, sequential orders) and the add/edit row (order 100). */
    private static final int ORDER_CUSTOM_ENGINE = 99;

    private static final int CUSTOM_NAME_MAX_LENGTH = 40;

    @Inject
    SearchRepository mSearchRepository;

    private RadioButtonPreference[] radioButtonPreferences;

    /** The user-defined engine's radio row; null while no custom engine exists. */
    private RadioButtonPreference customEnginePreference;

    private Preference addCustomPreference;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_search, rootKey);

        String[] mTempArray = getResources().getStringArray(R.array.settings_search_preference);

        int len = mTempArray.length;

        PreferenceScreen preferenceScreen = getPreferenceScreen();

        radioButtonPreferences = new RadioButtonPreference[len];

        for (int i = 0; i < len; i++) {
            radioButtonPreferences[i] = preferenceScreen.findPreference(mTempArray[i]);
            assert radioButtonPreferences[i] != null;
            radioButtonPreferences[i].setOnPreferenceClickListener(this);
        }

        addCustomPreference = preferenceScreen.findPreference(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_ADD);
        if (addCustomPreference != null) {
            addCustomPreference.setOnPreferenceClickListener(this);
        }

        refreshCustomEngineRow();
        syncCheckedStates();
    }


    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        String key = preference.getKey();

        if (Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_ADD.equals(key)) {
            showCustomEngineDialog();
            return true;
        }

        if (Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM.equals(key)) {
            mSearchRepository.setSearchEngine(Preferences.CUSTOM_SEARCH_ENGINE);
            syncCheckedStates();
            return true;
        }

        String[] mNameArray = getResources().getStringArray(R.array.settings_search);

        for (int i = 0; i < radioButtonPreferences.length; i++) {
            if (key.equals(radioButtonPreferences[i].getKey())) {
                mSearchRepository.setSearchEngine(mNameArray[i]);
                break;
            }
        }
        syncCheckedStates();
        return true;
    }

    /**
     * Single source of truth for the radio states: derive every row's checked
     * flag from the stored selection. Replaces the pairwise
     * GroupableRadioButton wiring — the group membership is dynamic now (the
     * custom row comes and goes), and re-registering listener lists on every
     * rebuild would accumulate duplicates.
     */
    private void syncCheckedStates() {
        String selection = mSharedPreferences.getString(
                Preferences.SETTINGS_SEARCH_ENGINE, Preferences.DEFAULT_SEARCH_ENGINE);

        String[] mNameArray = getResources().getStringArray(R.array.settings_search);

        for (int i = 0; i < radioButtonPreferences.length; i++) {
            radioButtonPreferences[i].setChecked(mNameArray[i].equals(selection));
        }
        if (customEnginePreference != null) {
            customEnginePreference.setChecked(Preferences.CUSTOM_SEARCH_ENGINE.equals(selection));
        }
    }

    /** (Re)builds the custom engine's radio row and flips the add/edit row's title. */
    private void refreshCustomEngineRow() {
        PreferenceScreen screen = getPreferenceScreen();

        if (customEnginePreference != null) {
            screen.removePreference(customEnginePreference);
            customEnginePreference = null;
        }

        if (mSearchRepository.hasCustomEngine()) {
            RadioButtonPreference preference = new RadioButtonPreference(requireContext());
            preference.setKey(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM);
            preference.setTitle(mSearchRepository.getCustomEngineName());
            preference.setIcon(R.drawable.ic_search_24);
            preference.setPersistent(false);
            preference.setOrder(ORDER_CUSTOM_ENGINE);
            preference.setOnPreferenceClickListener(this);
            screen.addPreference(preference);
            customEnginePreference = preference;
        }

        if (addCustomPreference != null) {
            addCustomPreference.setTitle(mSearchRepository.hasCustomEngine()
                    ? R.string.settings_search_custom_edit
                    : R.string.settings_search_custom_add);
        }
    }

    private void showCustomEngineDialog() {
        boolean editing = mSearchRepository.hasCustomEngine();

        View view = getLayoutInflater().inflate(R.layout.dialog_custom_search_engine, null);

        TextInputLayout nameLayout = view.findViewById(R.id.custom_engine_name_layout);
        TextInputLayout urlLayout = view.findViewById(R.id.custom_engine_url_layout);
        TextInputEditText nameField = view.findViewById(R.id.custom_engine_name);
        TextInputEditText urlField = view.findViewById(R.id.custom_engine_url);
        TextInputEditText suggestionField = view.findViewById(R.id.custom_engine_suggestion);
        TextInputLayout suggestionLayout = view.findViewById(R.id.custom_engine_suggestion_layout);

        if (editing) {
            nameField.setText(mSearchRepository.getCustomEngineName());
            urlField.setText(mSearchRepository.getCustomSearchUrl());
            suggestionField.setText(mSearchRepository.getCustomSuggestionUrl());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(editing
                        ? R.string.settings_search_custom_edit
                        : R.string.settings_search_custom_add)
                .setView(view)
                // null listener — the click is overridden below so a failed
                // validation keeps the dialog open instead of dismissing it.
                .setPositiveButton(R.string.prompt_save_confirmation, null)
                .setNegativeButton(R.string.cancel, null);

        if (editing) {
            builder.setNeutralButton(R.string.delete, (d, w) -> confirmDeleteCustomEngine());
        }

        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = text(nameField);
                    String url = text(urlField);
                    String suggestion = text(suggestionField);

                    boolean valid = validateName(nameLayout, name);
                    valid &= validateTemplate(urlLayout, url, true);
                    valid &= validateTemplate(suggestionLayout, suggestion, false);
                    if (!valid) return;

                    mSearchRepository.setCustomEngine(name, url, suggestion);
                    refreshCustomEngineRow();
                    syncCheckedStates();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    private void confirmDeleteCustomEngine() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_search_custom_delete_title)
                .setMessage(getString(R.string.settings_search_custom_delete_message,
                        mSearchRepository.getCustomEngineName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    // The repository resets the selection to the default
                    // engine when the deleted engine was the selected one.
                    mSearchRepository.removeCustomEngine();
                    refreshCustomEngineRow();
                    syncCheckedStates();
                })
                .show();
    }

    private static String text(TextInputEditText field) {
        CharSequence value = field.getText();
        return value == null ? "" : value.toString().trim();
    }

    private boolean validateName(TextInputLayout layout, String name) {
        if (TextUtils.isEmpty(name) || name.length() > CUSTOM_NAME_MAX_LENGTH) {
            layout.setError(getString(R.string.settings_search_custom_error_name));
            return false;
        }
        // A name colliding with a built-in would make the two rows
        // indistinguishable everywhere the current engine is matched by its
        // display name (sheet checked-state, suggestion parser branch).
        if (mSearchRepository.isBuiltInEngine(name)
                || Preferences.CUSTOM_SEARCH_ENGINE.equalsIgnoreCase(name)) {
            layout.setError(getString(R.string.settings_search_custom_error_name_taken));
            return false;
        }
        layout.setError(null);
        return true;
    }

    /**
     * A valid template is an http(s) URL containing the {@code %s}
     * placeholder and no other {@code %}: the search template is consumed by
     * {@code String.format}, where a stray {@code %} (incl. percent-encoded
     * characters like {@code %20}) throws at search time.
     */
    private boolean validateTemplate(TextInputLayout layout, String url, boolean required) {
        if (TextUtils.isEmpty(url)) {
            if (required) {
                layout.setError(getString(R.string.settings_search_custom_error_url));
                return false;
            }
            layout.setError(null);
            return true;
        }
        boolean valid = URLUtil.isNetworkUrl(url)
                && url.contains("%s")
                && !url.replace("%s", "").contains("%");
        layout.setError(valid ? null : getString(R.string.settings_search_custom_error_url));
        return valid;
    }
}
