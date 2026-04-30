package com.solarized.firedown.data.entity;


public class AutoCompleteEntity {

    public static final int SEARCH = 0;

    public static final int RESULTS = 1;

    public static final int HISTORY = 2;

    public static final int TAB = 3;

    public int uid;

    public int sessionId;
    public int drawableId;

    public int titleId;

    public int type;

    public String text;

    public String icon;

    public String title;

    public void setSubText(String url){
        this.text = url;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public void setTitle(String title){
        this.title = title;
    }

    public void setUid(int uid) {
        this.uid = uid;
    }

    public String getSubText(){
        return text;
    }

    public String getTitle(){
        return title;
    }

    public int getId() {
        return uid;
    }

    public int getType() {
        return type;
    }

    public void setType(int type){
        this.type = type;
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setTitleId(int titleId) {
        this.titleId = titleId;
    }

    public int getTitleId() {
        return titleId;
    }

    public void setDrawableId(int drawableId) {
        this.drawableId = drawableId;
    }

    public int getDrawableId() {
        return drawableId;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getIcon() {
        return icon;
    }

    public AutoCompleteEntity(){

    }

    public AutoCompleteEntity(int type, int uid, int titleId){
        this.type = type;
        this.uid = uid;
        this.titleId = titleId;
    }

    public AutoCompleteEntity(int type, int uid, int titleId, String title){
        this.type = type;
        this.uid = uid;
        this.titleId = titleId;
        this.title = title;
    }
}
