package com.solarized.firedown.data.entity;

public class SettingsEntity {

    public static final int TYPE_HEADER = 0;

    public static final int TYPE_SETTINGS = 1;

    public static final int TYPE_SETTINGS_SWITCH = 2;

    int id;

    String text;

    int type;

    public String getText(){
        return text;
    }

    public int getType(){
        return type;
    }

    public int getId(){
        return id;
    }

    public SettingsEntity(int id, String text, int type){
        this.id = id;
        this.text = text;
        this.type = type;
    }
}
