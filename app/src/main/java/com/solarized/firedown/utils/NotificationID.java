package com.solarized.firedown.utils;

import java.util.concurrent.atomic.AtomicInteger;

public class NotificationID {
    private final static AtomicInteger c = new AtomicInteger(1001);

    public static final int APK_UPDATE_PROMPT_INSTALL         = 666;

    public static final int APK_UPDATE_FAILED_INSTALL         = 667;

    public static final int APK_UPDATE_SUCCESSFUL_INSTALL     = 668;

    /**
     * Android's own install confirmation, when Firedown could not open it
     * (the app was in the background): its tap opens that dialog.
     */
    public static final int APK_UPDATE_CONFIRM_INSTALL        = 669;

    /** An update check run as an expedited job on Android 11 and older (its foreground notice). */
    public static final int APK_UPDATE_CHECK                  = 670;

    public static final int    MEDIA_ID    = 102;

    /**
     * Local-file background playback (PlayerPlaybackService). Distinct from
     * MEDIA_ID so browser media and the downloads player can notify at the
     * same time without clobbering each other.
     */
    public static final int PLAYER_MEDIA_ID = 103;

    /**
     * "Send directly" transfer (P2pShareService): the ongoing progress
     * notification and, detached at the end, the outcome.
     */
    public static final int P2P_SHARE_ID = 104;

    /**
     * A media or vault task in flight (TaskManager: GIF, audio, frame,
     * archive, vault lock and unlock), foreground with its progress.
     */
    public static final int TASK_ID = 105;

    /**
     * An add-on update that asks for new permissions waits for the user
     * (AddonUpdateNotification): one notification for every such update.
     */
    public static final int ADDON_UPDATE_REVIEW = 106;

    public static final int PERMISSIONS = 101;

    public static final int RUNNABLE_ID = 1000;


    public static int getID() {
        return c.incrementAndGet();
    }
}