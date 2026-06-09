package com.solarized.firedown.data;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

public class OptionItem {
    private final String label;
    @DrawableRes
    private final int iconRes;
    @StringRes
    private final int labelRes;
    /** Optional trailing (drawableEnd) icon, e.g. a chevron on a row that
     *  opens a sub-list. 0 = none. */
    @DrawableRes
    private final int trailingIconRes;

    public OptionItem(String label, @DrawableRes int iconRes, @StringRes int labelRes) {
        this.label = label;
        this.iconRes = iconRes;
        this.labelRes = labelRes;
        this.trailingIconRes = 0;
    }


    public OptionItem(String label, @DrawableRes int iconRes) {
        this.label = label;
        this.iconRes = iconRes;
        this.labelRes = 0;
        this.trailingIconRes = 0;
    }

    /** Row with a leading icon and a trailing icon (e.g. a "Media tools ›"
     *  group row whose chevron signals it opens a sub-list). */
    public OptionItem(String label, @DrawableRes int iconRes, @StringRes int labelRes,
                      @DrawableRes int trailingIconRes) {
        this.label = label;
        this.iconRes = iconRes;
        this.labelRes = labelRes;
        this.trailingIconRes = trailingIconRes;
    }

    public OptionItem(String label) {
        this.label = label;
        this.iconRes = 0;
        this.labelRes = 0;
        this.trailingIconRes = 0;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasIcon() { return iconRes != 0; }

    @DrawableRes
    public int getIconRes() {
        return iconRes;
    }

    @DrawableRes
    public int getTrailingIconRes() {
        return trailingIconRes;
    }

    @StringRes
    public int getLabelRes() {
        return labelRes;
    }
}