package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;


public class DohEditPreference extends Preference {

    private TextInputLayout textInputLayout;
    private TextInputEditText textInputEditText;

    private OnValidationRequestedListener listener;

    private CharSequence mCurrentUrl;

    private final Context mContext;

    public interface OnValidationRequestedListener {
        void onValidationRequested(String url);
    }

    public DohEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public void setOnValidationRequestedListener(OnValidationRequestedListener listener) {
        this.listener = listener;
    }

    public void setTextInputText(CharSequence charSequence){
        mCurrentUrl = charSequence;
        if(textInputEditText != null){
            textInputEditText.setText(charSequence);
            persistString(charSequence.toString());
        }
    }


    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        textInputLayout = (TextInputLayout) holder.findViewById(R.id.preference_input_layout);
        textInputEditText = (TextInputEditText) holder.findViewById(R.id.preference_input_text);

        textInputEditText.setText(getPersistedString(""));

        textInputEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                String url = textInputEditText.getText().toString();
                if(!URLUtil.isHttpsUrl(url)){
                    showError(mContext.getString(R.string.preference_doh_provider_custom_dialog_error_https));
                }else if(!URLUtil.isValidUrl(url)){
                    showError(mContext.getString(R.string.preference_doh_provider_custom_dialog_error_invalid));
                }else{
                    if(listener != null){
                        listener.onValidationRequested(url);
                    }else {
                        showSuccess(mContext.getString(R.string.settings_doh_server_custom), url);
                    }
                }
                return true;
            }
            return false;
        });
    }

    public void showError(String message) {
        if (textInputLayout != null) {
            textInputLayout.setError(message);
            textInputLayout.setErrorEnabled(true);
            textInputLayout.setHelperText(null);
            textInputLayout.setHelperTextEnabled(false);
        }
    }

    public void showSuccess(String message, String normalizedUrl) {
        if (textInputLayout != null) {
            textInputLayout.setError(null);
            textInputLayout.setErrorEnabled(false);
            textInputLayout.setHelperText(message);
            textInputLayout.setHelperTextEnabled(true);
            persistString(normalizedUrl);
            notifyChanged();
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && textInputLayout != null) {
            imm.hideSoftInputFromWindow(textInputLayout.getWindowToken(), 0);
        }
    }
}
