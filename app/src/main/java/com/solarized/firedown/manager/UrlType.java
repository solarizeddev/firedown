package com.solarized.firedown.manager;

public enum UrlType {

    UNKNOWN(-1), DUMMY(0), FILE(1), GECKO(2),
    MEDIA(3), IMAGE(4), SVG(5), TIMEDTEXT(6), TS(7), SABR(8);

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