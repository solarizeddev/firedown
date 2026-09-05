package com.solarized.firedown.manager;

public enum UrlType {

    UNKNOWN(-1), DUMMY(0), FILE(1), GECKO(2),
    MEDIA(3), IMAGE(4), SVG(5), TIMEDTEXT(6), TS(7), SABR(8), SUBTITLE(9), HLS_MASTER(10),
    // Mega.nz folder-link file: a zero-knowledge AES capture. The media URL alone
    // is undownloadable (the bytes are AES-CTR ciphertext), so — like SABR — it
    // needs side-channel data (the per-file key) and a dedicated strategy
    // (MegaStrategy) that resolves the temp URL and decrypts the stream.
    MEGA(11),
    // Deezer full track: same shape as MEGA. The captured synthetic URL
    // (www.deezer.com/track/<SNG_ID>?fmt=…) carries the decrypt input (SNG_ID) and
    // the session cookie; the wire only ever serves Blowfish-CBC-striped
    // ciphertext, so DeezerStrategy resolves the real CDN URL (getUserData →
    // song.getData → get_url) and Blowfish-decrypts the stream. NOT DRM — the key
    // is derived offline from SNG_ID + a static secret (see DeezerCrypto).
    DEEZER(12);

    private final int value;

    private UrlType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public boolean usesFFmpeg() {
        return this == MEDIA || this == TS;
    }

    public static UrlType getType(int type) {
        for (UrlType urlType : UrlType.values()) {
            if (urlType.value == type)
                return urlType;
        }
        return DUMMY;
    }
}