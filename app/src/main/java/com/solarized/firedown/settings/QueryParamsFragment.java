package com.solarized.firedown.settings;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceCategory;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.RemovableCheckBoxPreference;

import dagger.hilt.android.AndroidEntryPoint;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.mozilla.geckoview.ContentBlocking;

@AndroidEntryPoint
public class QueryParamsFragment extends BasePreferenceFragment {

    private static final String KEY_ADD = "com.solarized.firedown.preferences.browser.tracking.strip.add";
    private static final String KEY_LIST_CATEGORY = "com.solarized.firedown.preferences.browser.tracking.strip.category";

    private PreferenceCategory mListCategory;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_query_params, rootKey);

        EditTextPreference addPref = findPreference(KEY_ADD);
        if (addPref != null) {
            addPref.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setHint(R.string.settings_query_param_add_hint);
            });
            addPref.setOnPreferenceChangeListener((p, value) -> {
                String name = value == null ? "" : value.toString().trim();
                addParam(name);
                return false;
            });
        }

        mListCategory = findPreference(KEY_LIST_CATEGORY);

        rebuildList();
        tintIcons();
    }

    private Set<String> activeSet() {
        return parse(mSharedPreferences.getString(
                Preferences.SETTINGS_ANTI_TRACKING_STRIP_LIST,
                Preferences.DEFAULT_QUERY_STRIP_LIST));
    }

    private Set<String> userSet() {
        return parse(mSharedPreferences.getString(
                Preferences.SETTINGS_ANTI_TRACKING_USER_PARAMS, ""));
    }

    private Set<String> defaultSet() {
        return parse(Preferences.DEFAULT_QUERY_STRIP_LIST);
    }

    private static Set<String> parse(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return out;
        out.addAll(Arrays.asList(trimmed.split("\\s+")));
        return out;
    }

    private static String join(Set<String> set) {
        return String.join(" ", set);
    }

    private void persistActive(Set<String> active) {
        mSharedPreferences.edit()
                .putString(Preferences.SETTINGS_ANTI_TRACKING_STRIP_LIST, join(active))
                .apply();
        applyToGecko(active);
    }

    private void persistUser(Set<String> user) {
        mSharedPreferences.edit()
                .putString(Preferences.SETTINGS_ANTI_TRACKING_USER_PARAMS, join(user))
                .apply();
    }

    private void applyToGecko(Set<String> active) {
        ContentBlocking.Settings cb = mGeckoRuntimeHelper.getGeckoRuntime()
                .getSettings().getContentBlocking();
        cb.setQueryParameterStrippingStripList(active.toArray(new String[0]));
    }

    private void addParam(@NonNull String name) {
        if (!isValid(name)) {
            Toast.makeText(requireContext(), R.string.settings_query_param_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> active = activeSet();
        Set<String> user = userSet();
        Set<String> defaults = defaultSet();
        boolean alreadyKnown = active.contains(name) || defaults.contains(name) || user.contains(name);
        if (alreadyKnown && active.contains(name)) {
            Toast.makeText(requireContext(),
                    getString(R.string.settings_query_param_already_exists, name),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!defaults.contains(name)) {
            user.add(name);
            persistUser(user);
        }
        active.add(name);
        persistActive(active);
        rebuildList();
    }

    private void deleteUserParam(@NonNull String name) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_query_param_remove_title)
                .setMessage(getString(R.string.settings_query_param_remove_message, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_query_param_remove_confirm, (d, w) -> {
                    Set<String> user = userSet();
                    Set<String> active = activeSet();
                    user.remove(name);
                    active.remove(name);
                    persistUser(user);
                    persistActive(active);
                    rebuildList();
                })
                .show();
    }

    private void toggleParam(@NonNull String name, boolean checked) {
        Set<String> active = activeSet();
        if (checked) {
            active.add(name);
        } else {
            active.remove(name);
        }
        persistActive(active);
    }

    private static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    private void rebuildList() {
        if (mListCategory == null) return;
        mListCategory.removeAll();

        Set<String> defaults = defaultSet();
        Set<String> user = userSet();
        Set<String> active = activeSet();

        Set<String> universe = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        universe.addAll(defaults);
        universe.addAll(user);

        for (String name : universe) {
            boolean isUser = user.contains(name) && !defaults.contains(name);
            CheckBoxPreference cbp;
            if (isUser) {
                RemovableCheckBoxPreference rcbp = new RemovableCheckBoxPreference(requireContext());
                rcbp.setSummary(R.string.settings_query_param_user_summary);
                rcbp.setOnLongClickListener(v -> {
                    deleteUserParam(name);
                    return true;
                });
                cbp = rcbp;
            } else {
                cbp = new CheckBoxPreference(requireContext());
            }
            cbp.setKey("strip_param_" + name);
            cbp.setTitle(name);
            cbp.setPersistent(false);
            cbp.setChecked(active.contains(name));
            cbp.setOnPreferenceChangeListener((p, newValue) -> {
                toggleParam(name, Boolean.TRUE.equals(newValue));
                return true;
            });
            mListCategory.addPreference(cbp);
        }
    }
}
