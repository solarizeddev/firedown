package com.solarized.firedown.settings;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.RadioButtonPreference;


public class SearchFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = SearchFragment.class.getName();

    private RadioButtonPreference[] radioButtonPreferences;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_search, rootKey);

        String[] mTempArray = getResources().getStringArray(R.array.settings_search_preference);

        String[] mEngineArray = getResources().getStringArray(R.array.settings_search);

        String mCurrentSearchEngine = mSharedPreferences.getString(Preferences.SETTINGS_SEARCH_ENGINE, Preferences.DEFAULT_SEARCH_ENGINE);

        int len = mTempArray.length;

        PreferenceScreen preferenceScreen = getPreferenceScreen();

        radioButtonPreferences = new RadioButtonPreference[len];

        for(int i = 0; i < len; i++){
            radioButtonPreferences[i] = preferenceScreen.findPreference(mTempArray[i]);
            assert radioButtonPreferences[i] != null;
            radioButtonPreferences[i].setOnPreferenceClickListener(this);
            radioButtonPreferences[i].setChecked(mEngineArray[i].equals(mCurrentSearchEngine));
        }

        for(int i = 0; i < len; i++){
            radioButtonPreferences[i] = preferenceScreen.findPreference(mTempArray[i]);
            for(int j = 0; j < len; j++){
                if(j != i){
                    radioButtonPreferences[i].addToRadioGroup(radioButtonPreferences[j]);
                }
            }
        }

    }



    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        RadioButtonPreference groupableRadioButtons = (RadioButtonPreference) preference;
        groupableRadioButtons.toggleRadioButton();
        savePreference(groupableRadioButtons);
        return true;
    }

    private void savePreference(Preference preference){
        String[] mNameArray = getResources().getStringArray(R.array.settings_search);

        for(int i = 0; i < radioButtonPreferences.length; i ++){
            RadioButtonPreference radioButtonPreference = radioButtonPreferences[i];
            if(preference.getKey().equals(radioButtonPreference.getKey())){
                mSharedPreferences.edit().putString(Preferences.SETTINGS_SEARCH_ENGINE, mNameArray[i]).apply();
                break;
            }
        }
    }
}