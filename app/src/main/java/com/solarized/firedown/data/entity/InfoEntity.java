package com.solarized.firedown.data.entity;

public class InfoEntity {


    public static final int ITEM = 0;

    public static final int ITEM_FINAL = 1;

    int id;

    int type;

    String text;

    public InfoEntity(int id, int type, String text){
        this.id = id;
        this.type = type;
        this.text = text;
    }

    public InfoEntity(){

    }

    public void setText(String text) {
        this.text = text;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public int getId() {
        return id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }
}
