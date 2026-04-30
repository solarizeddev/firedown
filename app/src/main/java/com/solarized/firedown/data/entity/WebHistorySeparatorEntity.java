package com.solarized.firedown.data.entity;

public class WebHistorySeparatorEntity extends WebHistoryEntity{

    private int id;
    private int titleResId; // Store the R.string ID here

    public int getTitleResId() { return titleResId; }
    public void setTitleResId(int titleResId) { this.titleResId = titleResId; }


}
