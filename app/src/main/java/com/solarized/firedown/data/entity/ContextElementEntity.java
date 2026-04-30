package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import org.mozilla.geckoview.GeckoSession;

public class ContextElementEntity implements Parcelable {

    int type;

    String altText;

    String baseUri;

    String title;

    String linkUri;

    String srcUri;

    String textContent;



    public ContextElementEntity(GeckoSession.ContentDelegate.ContextElement element){
        type = element.type;
        altText = element.altText;
        baseUri = element.baseUri;
        title = element.title;
        linkUri = element.linkUri;
        srcUri = element.srcUri;
        textContent = element.textContent;
    }

    protected ContextElementEntity(Parcel in) {
        type = in.readInt();
        altText = in.readString();
        baseUri = in.readString();
        title = in.readString();
        linkUri = in.readString();
        srcUri = in.readString();
        textContent = in.readString();
    }

    public static final Creator<ContextElementEntity> CREATOR = new Creator<>() {
        @Override
        public ContextElementEntity createFromParcel(Parcel in) {
            return new ContextElementEntity(in);
        }

        @Override
        public ContextElementEntity[] newArray(int size) {
            return new ContextElementEntity[size];
        }
    };

    public int getType() {
        return type;
    }

    public String getAltText() {
        return altText;
    }

    public String getBaseUri() {
        return baseUri;
    }

    public String getLinkUri() {
        return linkUri;
    }

    public String getSrcUri() {
        return srcUri;
    }

    public String getTextContent() {
        return textContent;
    }

    public String getTitle() {
        return title;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeInt(type);
        parcel.writeString(altText);
        parcel.writeString(baseUri);
        parcel.writeString(title);
        parcel.writeString(linkUri);
        parcel.writeString(srcUri);
        parcel.writeString(textContent);
    }
}
