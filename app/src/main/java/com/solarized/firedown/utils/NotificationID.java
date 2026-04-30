package com.solarized.firedown.utils;

import java.util.concurrent.atomic.AtomicInteger;

public class NotificationID {
    private final static AtomicInteger c = new AtomicInteger(1001);

    public static final int APK_UPDATE_PROMPT_INSTALL         = 666;

    public static final int APK_UPDATE_FAILED_INSTALL         = 667;

    public static final int APK_UPDATE_SUCCESSFUL_INSTALL     = 668;

    public static final int    MEDIA_ID    = 102;

    public static final int PERMISSIONS = 101;

    public static final int RUNNABLE_ID = 1000;


    public static int getID() {
        return c.incrementAndGet();
    }
}