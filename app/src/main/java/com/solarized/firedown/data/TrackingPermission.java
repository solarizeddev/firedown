package com.solarized.firedown.data;

public interface TrackingPermission {

    int getId();

    String getOrigin();

    long getDate();

    boolean isTrackingEnabled();
}
