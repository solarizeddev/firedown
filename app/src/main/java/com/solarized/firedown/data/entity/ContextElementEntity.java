package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.solarized.firedown.utils.DataUris;

import org.mozilla.geckoview.GeckoSession;

public class ContextElementEntity implements Parcelable {

    int type;

    String altText;

    String baseUri;

    String title;

    String linkUri;

    String srcUri;




    public ContextElementEntity(GeckoSession.ContentDelegate.ContextElement element){
        type = element.type;
        altText = element.altText;
        baseUri = element.baseUri;
        title = element.title;
        linkUri = element.linkUri;
        srcUri = element.srcUri;;
    }

    private ContextElementEntity(ContextElementEntity other) {
        type = other.type;
        altText = other.altText;
        baseUri = other.baseUri;
        title = other.title;
        linkUri = other.linkUri;
        srcUri = other.srcUri;
    }

    /**
     * This element with a data: src cut to its header, for a Bundle: saved
     * state is sized for an address, and a data: image's URL is the image.
     * The element itself (the tab keeps it) is what a dialog reads back.
     */
    public ContextElementEntity withoutDataPayload() {
        if (!DataUris.hasPayload(srcUri)) {
            return this;
        }
        ContextElementEntity copy = new ContextElementEntity(this);
        copy.srcUri = DataUris.stripPayload(srcUri);
        return copy;
    }

    protected ContextElementEntity(Parcel in) {
        type = in.readInt();
        altText = in.readString();
        baseUri = in.readString();
        title = in.readString();
        linkUri = in.readString();
        srcUri = in.readString();
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
    }
}
