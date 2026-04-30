package com.solarized.firedown.data;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

public class OptionItem {
    private final String label;
    @DrawableRes
    private final int iconRes;
    @StringRes
    private final int labelRes;

    public OptionItem(String label, @DrawableRes int iconRes, @StringRes int labelRes) {
        this.label = label;
        this.iconRes = iconRes;
        this.labelRes = labelRes;
    }


    public OptionItem(String label, @DrawableRes int iconRes) {
        this.label = label;
        this.iconRes = iconRes;
        this.labelRes = 0;
    }

    public OptionItem(String label) {
        this.label = label;
        this.iconRes = 0;
        this.labelRes = 0;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasIcon() { return iconRes != 0; }

    @DrawableRes
    public int getIconRes() {
        return iconRes;
    }

    @StringRes
    public int getLabelRes() {
        return labelRes;
    }
}