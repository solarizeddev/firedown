package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class WebOptionEntity implements Parcelable {

    String mUrl;

    int mResId;

    protected WebOptionEntity(Parcel in) {
        mUrl = in.readString();
        mResId = in.readInt();
    }

    public static final Creator<WebOptionEntity> CREATOR = new Creator<WebOptionEntity>() {
        @Override
        public WebOptionEntity createFromParcel(Parcel in) {
            return new WebOptionEntity(in);
        }

        @Override
        public WebOptionEntity[] newArray(int size) {
            return new WebOptionEntity[size];
        }
    };

    public String getUrl(){
        return mUrl;
    }


    public int getId(){
        return mResId;
    }


    public WebOptionEntity(int res, String url){
        mUrl = url;
        mResId = res;
    }

    public WebOptionEntity(WebOptionEntity webOptionEntity){
        mUrl = webOptionEntity.getUrl();
        mResId = webOptionEntity.getId();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mUrl);
        dest.writeInt(mResId);
    }
}
