package com.solarized.firedown.data.entity;

public class ToolbarMenuEntity {

    String resText;

    int resIcon;

    int type;

    int id;

    int resColor;


    public int getId() {
        return id;
    }

    public int getResIcon() {
        return resIcon;
    }

    public String getResText() {
        return resText;
    }

    public int getType() {
        return type;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setResIcon(int resIcon) {
        this.resIcon = resIcon;
    }

    public void setResText(String resText) {
        this.resText = resText;
    }

    public void setType(int type) {
        this.type = type;
    }

    public void setResColor(int resColor) {
        this.resColor = resColor;
    }

    public int getResColor() {
        return resColor;
    }
}
