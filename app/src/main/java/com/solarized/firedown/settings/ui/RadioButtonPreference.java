package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.CheckBoxPreference;

import com.solarized.firedown.R;

import java.util.ArrayList;
import java.util.List;

public class RadioButtonPreference extends CheckBoxPreference implements GroupableRadioButton {

    private final List<GroupableRadioButton> mListeners = new ArrayList<>();

    public RadioButtonPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setWidgetLayoutResource(R.layout.preference_widget_radiobutton);
    }

    public RadioButtonPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_widget_radiobutton);
    }

    public RadioButtonPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onClick() {
        if (this.isChecked()) {
            return;
        }
        super.onClick();
    }

    @Override
    public void updateRadioValue(boolean isChecked) {
        setChecked(isChecked);
    }

    @Override
    public void addToRadioGroup(GroupableRadioButton radioButton) {
        mListeners.add(radioButton);
    }

    public void toggleRadioButton() {
        if(isChecked()){
            for (GroupableRadioButton radioButton : mListeners){
                radioButton.updateRadioValue(false);
            }
        }

    }

}
