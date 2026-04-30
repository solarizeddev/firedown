package com.solarized.firedown.ui;

public interface OnItemClickListener {
    void onItemClick(int position, int resId);

    void onLongClick(int position, int resId);

    void onItemVariantClick(int position, int variant, int resId);
}
