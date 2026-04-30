package com.solarized.firedown.settings.ui;

public interface GroupableRadioButton {

    void updateRadioValue(boolean isChecked);

    void addToRadioGroup(GroupableRadioButton radioButton);
}
