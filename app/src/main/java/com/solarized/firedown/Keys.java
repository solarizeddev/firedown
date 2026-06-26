package com.solarized.firedown;

public class Keys {

    public static final String DOWNLOAD_REQUEST = "com.solarized.firedown.DOWNLOAD_REQUEST";
    public static final String DOWNLOAD_REQUEST_LIST = "com.solarized.firedown.DOWNLOAD_REQUEST_LIST";
    public static final String MEDIA_POSITION = "com.solarized.firedown.MEDIA_POSITION";
    public static final String MEDIA_DURATION = "com.solarized.firedown.MEDIA_DURATION";
    public static final String MEDIA_RATE = "com.solarized.firedown.MEDIA_RATE";
    public static final String SHARE_URL = "com.solarized.firedown.SHARE_URL";
    public static final String EDIT = "com.solarized.firedown.EDIT";
    public static final String TITLE = "com.solarized.firedown.TITLE";
    public static final String IS_INCOGNITO = "com.solarized.firedown.IS_INCOGNTO";
    public static final String OPEN_INCOGNITO = "com.solarized.firedown.OPEN_INCOGNTO";
    public static final String ITEM_BOOKMARK = "com.solarized.firedown.ITEM_BOOKMARK";
    public static final String ITEM_URL = "com.solarized.firedown.ITEM_URL";
    public static final String ITEM_ID = "com.solarized.firedown.ITEM_ID";
    public static final String BLOCK_STORE_REDIRECT = "com.solarized.firedown.BLOCK_STORE_REDIRECT";
    public static final String ITEM_IS_HOME = "com.solarized.firedown.ITEM_IS_HOME";
    public static final String ITEM_POSITION = "com.solarized.firedown.ITEM_POSITION";
    public static final String ITEM_CONTAINS = "com.solarized.firedown.ITEM_CONTAINS";
    public static final String ITEM_LIST_ID = "com.solarized.firedown.ITEM_LIST_ID";
    public static final String INTENT_ACTION = "com.solarized.firedown.INTENT_ACTION";
    public static final String UPDATE_META = "com.solarized.firedown.UDPATE_META";
    public static final String UPDATE_NAME = "com.solarized.firedown.UPDATE_NAME";
    // DownloadManager hand-off: the worker enqueues the APK download and
    // records the enqueue id + the metadata UpdateDownloadReceiver needs to
    // verify the finished file (the broadcast only carries the download id).
    public static final String UPDATE_DOWNLOAD_ID = "com.solarized.firedown.UPDATE_DOWNLOAD_ID";
    public static final String UPDATE_DOWNLOAD_SHA = "com.solarized.firedown.UPDATE_DOWNLOAD_SHA";
    public static final String UPDATE_DOWNLOAD_NAME = "com.solarized.firedown.UPDATE_DOWNLOAD_NAME";
    public static final String UPDATE_DOWNLOAD_VERSION_CODE = "com.solarized.firedown.UPDATE_DOWNLOAD_VERSION_CODE";
    // The mirror candidate list + the index currently being tried, so a failed
    // download can fail over to the next mirror without waiting for the next
    // check (covers the Cloudflare/LaLiga-blocked-host case).
    public static final String UPDATE_DOWNLOAD_URLS = "com.solarized.firedown.UPDATE_DOWNLOAD_URLS";
    public static final String UPDATE_DOWNLOAD_URL_INDEX = "com.solarized.firedown.UPDATE_DOWNLOAD_URL_INDEX";
    // The "ready" record: an APK that has PASSED SHA-256 + signature verification
    // and is safe to offer for install (notification + in-app sheet). Distinct
    // from the download record above so the install shortcut never trusts an
    // unverified file (e.g. one whose completion broadcast was missed).
    public static final String UPDATE_READY = "com.solarized.firedown.UPDATE_READY";
    public static final String UPDATE_READY_VERSION_CODE = "com.solarized.firedown.UPDATE_READY_VERSION_CODE";
    public static final String UPDATE_READY_NAME = "com.solarized.firedown.UPDATE_READY_NAME";
    // Per-version "Later" suppression for the in-app update sheet (a newer
    // version resets it, since the value is the dismissed versionCode).
    public static final String UPDATE_PROMPT_DISMISSED_VERSION = "com.solarized.firedown.UPDATE_PROMPT_DISMISSED_VERSION";
    // Bounded-retry bookkeeping: how many full (all-mirrors) download rounds have
    // failed for UPDATE_FAILED_VERSION_CODE. Once it hits the cap the downloader
    // stops re-fetching that version on every check (a newer version resets it),
    // so a permanently-failing download can't loop forever.
    public static final String UPDATE_FAILED_VERSION_CODE = "com.solarized.firedown.UPDATE_FAILED_VERSION_CODE";
    public static final String UPDATE_FAILED_COUNT = "com.solarized.firedown.UPDATE_FAILED_COUNT";
    // Changelog for a version (from status.json), remembered by the worker at
    // check time so the in-app sheet can show it later — the sheet has no access
    // to the manifest at display time. Single slot, matched by versionCode.
    public static final String UPDATE_CHANGELOG_VERSION_CODE = "com.solarized.firedown.UPDATE_CHANGELOG_VERSION_CODE";
    public static final String UPDATE_CHANGELOG_TEXT = "com.solarized.firedown.UPDATE_CHANGELOG_TEXT";

    public static final String GIF_START_MS = "com.solarized.firedown.GIF_START_MS";
    public static final String GIF_END_MS = "com.solarized.firedown.GIF_END_MS";
    public static final String GIF_FPS = "com.solarized.firedown.GIF_FPS";
    public static final String GIF_WIDTH = "com.solarized.firedown.GIF_WIDTH";

    public static final String FRAME_POSITION_MS = "com.solarized.firedown.FRAME_POSITION_MS";

}
