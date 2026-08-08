package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * One selectable audio track of a multi-audio-track YouTube video
 * (auto-dubbing). Emitted by the youtube extension's
 * {@code buildAudioTrackOptions} — one entry per distinct track, each
 * carrying that track's best AAC rendition so a selection can swap the
 * download's audio wholesale: {@code url} for the FFmpegMergeStrategy
 * direct-URL path (empty on SABR-only captures), and
 * {@code itag}/{@code lastModified}/{@code xtags}/{@code id} forming the
 * SABR audio FormatId + AbrState track id for SabrStrategy.
 *
 * <p>The list only exists (size >= 2) for multi-track videos; the original
 * language track is flagged {@code original} and listed first.</p>
 */
public class AudioTrackEntity implements Parcelable {

    /** YouTube audio track id, e.g. "en-US.4" — sent as AbrState field 69. */
    private final String id;

    /** Localized display name from the player response, e.g.
     *  "English (United States) original", "Español (Latinoamérica)". */
    private final String name;

    /** True for the original-language track (the pre-selected default). */
    private final boolean original;

    /** The track's best AAC rendition — SABR audio FormatId fields. */
    private final int itag;
    private final String lastModified;
    private final String xtags;

    /** Direct googlevideo URL of that rendition (n-param-transformed), or
     *  empty for SABR-only captures where formats carry no URLs. */
    private final String url;

    public AudioTrackEntity(String id, String name, boolean original,
                            int itag, String lastModified, String xtags, String url) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "";
        this.original = original;
        this.itag = itag;
        this.lastModified = lastModified != null ? lastModified : "0";
        this.xtags = xtags != null ? xtags : "";
        this.url = url != null ? url : "";
    }

    protected AudioTrackEntity(Parcel in) {
        id = in.readString();
        name = in.readString();
        original = in.readByte() != 0;
        itag = in.readInt();
        lastModified = in.readString();
        xtags = in.readString();
        url = in.readString();
    }

    public static final Creator<AudioTrackEntity> CREATOR = new Creator<>() {
        @Override
        public AudioTrackEntity createFromParcel(Parcel in) {
            return new AudioTrackEntity(in);
        }

        @Override
        public AudioTrackEntity[] newArray(int size) {
            return new AudioTrackEntity[size];
        }
    };

    public String getId() { return id; }

    public String getName() { return name; }

    public boolean isOriginal() { return original; }

    public int getItag() { return itag; }

    public String getLastModified() { return lastModified; }

    public String getXtags() { return xtags; }

    public String getUrl() { return url; }

    /** BCP-47 language part of the track id ("en-US.4" → "en-US"), or the
     *  whole id when there is no type suffix. */
    public String getLanguageCode() {
        int dot = id.lastIndexOf('.');
        return dot > 0 ? id.substring(0, dot) : id;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeByte((byte) (original ? 1 : 0));
        dest.writeInt(itag);
        dest.writeString(lastModified);
        dest.writeString(xtags);
        dest.writeString(url);
    }
}
