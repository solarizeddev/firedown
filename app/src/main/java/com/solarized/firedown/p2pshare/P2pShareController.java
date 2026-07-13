package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.GalleryPublisher;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Java side of the P2P share engine — the bridge between the share screens
 * (P2pSendFragment / P2pReceiveFragment) and the WebRTC engine running in the
 * p2pshare extension's background page.
 *
 * <p>Ownership split (mirrors PoTokenGenerator): this controller owns the
 * long-lived native {@link WebExtension.Port} (handed over by
 * GeckoRuntimeHelper's onConnect), the loopback byte server, and the
 * post-transfer bookkeeping (verify + rename the received file, insert the
 * FINISHED download row). The extension owns RTCPeerConnection/DataChannel.
 * The fragments own all UI and the session-scoped WebRTC pref flip.
 *
 * <p>One session at a time by design — the share screens are modal
 * full-screen destinations, and the engine mirrors that with a single
 * `session` slot. Starting a new session tears down the previous one.
 *
 * <p>The engine-readiness dance ("ensure"): RTCPeerConnection is pref-gated
 * WebIDL evaluated at page-global creation, so a background page that loaded
 * while the user's WebRTC toggle was off has NO constructor even after the
 * fragment enables the pref. ensureEngine() probes with {type:"ensure"}; on
 * ok:false the page reloads itself, reconnects the port, and posts a fresh
 * {type:"ready", rtc:true}, at which point the queued action runs. Don't
 * simplify this into a plain "is the port connected" check.
 */
@Singleton
public class P2pShareController {

    private static final String TAG = "P2pShareController";

    /**
     * Native port name — must match connectNative() in
     * assets/p2pshare/background.js. Port names reject hyphens.
     */
    public static final String PORT_NAME = "p2pshare";

    /**
     * Code prefixes — must match OFFER_PREFIX/ANSWER_PREFIX in
     * assets/p2pshare/background.js. The UI uses them to validate a
     * scan/paste before handing it to the engine.
     */
    public static final String OFFER_PREFIX = "FDS1.";
    public static final String ANSWER_PREFIX = "FDR1.";

    private static final long ENGINE_READY_TIMEOUT_MS = 8000;

    private static final SecureRandom ID_RANDOM = new SecureRandom();

    /**
     * UI-facing events. All callbacks arrive on the main thread.
     */
    public interface Listener {
        /** A signaling code is ready to show (QR / share sheet). */
        void onCode(@NonNull String role, @NonNull String code);

        /** Receiver only: the scanned offer parsed — show the preview card. */
        void onOfferParsed(@NonNull String name, long size, @NonNull String mime, @NonNull String device);

        /** connecting | connected | closed */
        void onConnectionState(@NonNull String state);

        void onProgress(long done, long total, long rate);

        /** Transfer finished. For receive, the file row is already inserted. */
        void onDone(@NonNull String role, long bytes);

        /**
         * Errors: "bad-code" is soft (the current step stays valid, user
         * retries the scan/paste); everything else ends the session.
         */
        void onError(@NonNull String code, @NonNull String detail);
    }

    private final Context mContext;
    private final SharedPreferences mSharedPreferences;
    private final DownloadDataRepository mDownloadDataRepository;
    private final Executor mDiskExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private WebExtension.Port mPort;
    private boolean mEngineRtcReady;
    private Runnable mPendingEngineAction;
    private final Runnable mEngineTimeout = this::onEngineTimeout;
    // The engine deliberately reloads its page to pick up the freshly-enabled
    // WebRTC pref (see the ensure/reload dance). That reload disconnects the
    // port and reconnects — NOT a fatal transport loss. This flag lets
    // onDisconnect tell the expected reload apart from a real crash so it
    // doesn't tear down the session that is WAITING for the reload.
    private boolean mAwaitingReload;
    // Bounded re-ensure: the reloaded page may still land before the async
    // pref write is visible to its new global (rtc:false again); retry a few
    // times before giving up rather than dead-ending on a timing race.
    private int mEnsureRetries;
    private static final int MAX_ENSURE_RETRIES = 3;
    // Set by the fragment when it had to TEMPORARILY enable the WebRTC pref
    // for this session. In that case the engine page MUST be reloaded before
    // use even though `mEngineRtcReady` is a sticky singleton true from an
    // earlier share: restoring the pref to false at the previous session's end
    // left the un-reloaded page with a live RTCPeerConnection constructor but a
    // dead ICE stack (createOffer hangs). A forced reload re-initialises it
    // while the pref is on. Consumed on the next successful ready.
    private boolean mForceEngineReload;

    // Active session state. Main-thread only (all port + fragment traffic is
    // UI-thread), except the finalize step which hops to the disk executor.
    private Listener mListener;
    private String mRole; // "send" | "receive" | null
    private P2pLoopbackServer mServer;
    private String mRecvName;
    private String mRecvMime;
    private File mRecvPartFile;
    private boolean mRecvFinalized;
    // Set once bytes are actually moving (first progress event), cleared on
    // done/teardown — drives the "abandon transfer?" back-press confirm.
    private boolean mTransferring;
    // The finished download row, so the receiver's "Open" can act on it.
    private DownloadEntity mReceivedEntity;

    @Inject
    public P2pShareController(
            @ApplicationContext Context context,
            SharedPreferences sharedPreferences,
            DownloadDataRepository downloadDataRepository,
            @Qualifiers.DiskIO Executor diskExecutor
    ) {
        mContext = context;
        mSharedPreferences = sharedPreferences;
        mDownloadDataRepository = downloadDataRepository;
        mDiskExecutor = diskExecutor;
    }

    /**
     * Called by the share fragment when it TEMPORARILY enabled the WebRTC pref
     * for this session — forces the next engine (re)init to reload the page
     * (see {@link #mForceEngineReload}). Must be set before the first
     * start/accept call.
     */
    @UiThread
    public void setEngineNeedsReload(boolean needsReload) {
        mForceEngineReload = needsReload;
    }

    /* ── port lifecycle (called from GeckoRuntimeHelper) ────────────────── */

    @UiThread
    public void onPortConnected(@NonNull WebExtension.Port port) {
        mPort = port;
        port.setDelegate(new WebExtension.PortDelegate() {
            @Override
            public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port p) {
                if (message instanceof JSONObject json) {
                    handleEngineEvent(json);
                }
            }

            @Override
            public void onDisconnect(@NonNull WebExtension.Port p) {
                if (mPort == p) {
                    mPort = null;
                    mEngineRtcReady = false;
                    // The engine reconnects on its own (background.js retries);
                    // an ACTIVE transfer cannot survive the page going away —
                    // EXCEPT the deliberate ensure/reload, where the reconnect
                    // is expected and the pending action is released on the
                    // fresh "ready". Don't fail a session that's mid-reload.
                    if (mRole != null && mListener != null && !mAwaitingReload) {
                        postError("engine", "engine disconnected");
                    }
                }
            }
        });
    }

    /* ── session API (fragments, main thread) ───────────────────────────── */

    /**
     * Sender: serve {@code entity}'s file on the loopback and ask the engine
     * for an offer code. Events flow to {@code listener} until stop().
     */
    @UiThread
    public void startSend(@NonNull DownloadEntity entity, @NonNull Listener listener) {
        stopSession(false);
        mListener = listener;
        mRole = "send";

        String path = entity.getFilePath();
        if (path == null) {
            postError("file", "file unreadable");
            return;
        }
        long size = RestoredFileAccess.length(mContext, path);
        if (size <= 0) {
            postError("file", "file unreadable");
            return;
        }
        try {
            mServer = new P2pLoopbackServer(mContext);
            mServer.start();
            mServer.setReadFile(path);
        } catch (IOException e) {
            postError("engine", "loopback start failed");
            return;
        }

        JSONObject command = new JSONObject();
        try {
            command.put("type", "send-start");
            command.put("readUrl", mServer.getReadUrl());
            command.put("name", entity.getFileName());
            command.put("size", size);
            // Derive the mime from the file's OWN extension, not the entity's
            // stored label — that label is Firedown's internal/UI value (an
            // HLS capture muxed to .mp4 can carry a manifest mime) and would
            // mis-bucket the received download's type/icon/viewer.
            command.put("mime", FileUriHelper.getMimeTypeFromFile(new File(path).getName()));
            command.put("device", Build.MODEL);
            command.put("stun", Preferences.getP2pStunServer(mSharedPreferences));
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        ensureEngine(() -> postCommand(command));
    }

    /**
     * Sender: the scanned/pasted FDR1. reply code.
     */
    @UiThread
    public void provideAnswer(@NonNull String code) {
        JSONObject command = new JSONObject();
        try {
            command.put("type", "send-answer");
            command.put("code", code);
        } catch (JSONException e) {
            return;
        }
        postCommand(command);
    }

    /**
     * Receiver: parse a scanned/pasted FDS1. offer code. onOfferParsed fires
     * with the preview metadata; nothing connects until acceptOffer().
     */
    @UiThread
    public void startReceive(@NonNull String code, @NonNull Listener listener) {
        stopSession(false);
        mListener = listener;
        mRole = "receive";

        JSONObject command = new JSONObject();
        try {
            command.put("type", "recv-start");
            command.put("code", code);
            command.put("stun", Preferences.getP2pStunServer(mSharedPreferences));
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        ensureEngine(() -> postCommand(command));
    }

    /**
     * Receiver: user accepted the preview — create the target file, arm the
     * loopback write side, and let the engine answer.
     */
    @UiThread
    public void acceptOffer() {
        if (!"receive".equals(mRole) || mRecvName == null) {
            return;
        }
        try {
            StoragePaths.ensureDownloadPath(mContext);
            File target = buildTargetFile(mRecvName, mRecvMime);
            mRecvPartFile = new File(target.getParentFile(), target.getName() + ".part");
            if (mRecvPartFile.exists() && !mRecvPartFile.delete()) {
                throw new IOException("stale part file");
            }
            mServer = new P2pLoopbackServer(mContext);
            mServer.start();
            mServer.setWriteTarget(mRecvPartFile);
        } catch (IOException e) {
            postError("file", "cannot create target file");
            return;
        }

        JSONObject command = new JSONObject();
        try {
            command.put("type", "recv-accept");
            command.put("writeUrl", mServer.getWriteUrl());
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        postCommand(command);
    }

    /**
     * End the session: tells the engine to close the peer connection, stops
     * the loopback, deletes an unfinished .part. Called by the fragments in
     * onDestroyView — screen lifetime IS session lifetime.
     */
    @UiThread
    public void stop() {
        // Best-effort tell the engine to close; NEVER route through postCommand
        // (its no-port path posts a fatal onError back into a fragment that is
        // being torn down — e.g. Decline during the reload window flashed the
        // error card). Teardown proceeds regardless of the port state.
        if (mPort != null) {
            try {
                JSONObject command = new JSONObject();
                command.put("type", "stop");
                mPort.postMessage(command);
            } catch (JSONException e) {
                // Teardown proceeds regardless.
            }
        }
        stopSession(true);
    }

    /* ── engine events ──────────────────────────────────────────────────── */

    private void handleEngineEvent(JSONObject json) {
        String type = json.optString("type", "");
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "engine event: " + type);
        }
        switch (type) {
            case "ready" -> {
                mAwaitingReload = false;
                mEngineRtcReady = json.optBoolean("rtc", false);
                if (mEngineRtcReady) {
                    // The forced reload (if any) has happened and the page is
                    // freshly RTC-ready — consume the flag.
                    mForceEngineReload = false;
                    runPendingIfReady();
                } else if (mPendingEngineAction != null && mEnsureRetries < MAX_ENSURE_RETRIES) {
                    // Reloaded but the async pref write wasn't visible to the
                    // new global yet — re-ensure (another reload) a bounded
                    // number of times rather than dead-ending at the timeout.
                    mEnsureRetries++;
                    sendEnsure();
                }
            }
            case "ensure-result" -> {
                if (json.optBoolean("ok", false)) {
                    mEngineRtcReady = true;
                    mAwaitingReload = false;
                    runPendingIfReady();
                } else {
                    // ok:false → the page is about to reload; expect the port
                    // to drop and come back (guard onDisconnect). The fresh
                    // "ready" releases the pending action (or a retry / the
                    // timeout resolves it).
                    mAwaitingReload = true;
                }
            }
            case "code" -> {
                if (mListener != null) {
                    mListener.onCode(json.optString("role", ""), json.optString("code", ""));
                }
            }
            case "offer-parsed" -> {
                mRecvName = json.optString("name", "");
                mRecvMime = json.optString("mime", "");
                if (mListener != null) {
                    mListener.onOfferParsed(mRecvName, json.optLong("size", 0), mRecvMime,
                            json.optString("device", ""));
                }
            }
            case "state" -> {
                if (mListener != null) {
                    mListener.onConnectionState(json.optString("state", ""));
                }
            }
            case "progress" -> {
                mTransferring = true;
                if (mListener != null) {
                    mListener.onProgress(json.optLong("done", 0), json.optLong("total", 0), json.optLong("rate", 0));
                }
            }
            case "done" -> handleDone(json);
            case "error" -> {
                String code = json.optString("code", "engine");
                if ("bad-code".equals(code)) {
                    // Soft: the session (and the shown QR) stays valid.
                    if (mListener != null) {
                        mListener.onError(code, json.optString("detail", ""));
                    }
                } else {
                    postError(code, json.optString("detail", ""));
                }
            }
            default -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "unknown engine event: " + type);
                }
            }
        }
    }

    private void handleDone(JSONObject json) {
        String role = json.optString("role", "");
        long bytes = json.optLong("bytes", 0);
        mTransferring = false; // completed — no "abandon?" confirm past here
        if ("send".equals(role)) {
            if (mListener != null) {
                mListener.onDone(role, bytes);
            }
            return;
        }
        // Receive: verify + rename off the main thread, insert the FINISHED
        // row (Room invalidation refreshes the Downloads list on its own),
        // and make the file visible to gallery apps.
        final Listener listener = mListener;
        final File partFile = mRecvPartFile;
        final String name = mRecvName;
        final String mime = mRecvMime;
        if (mServer != null) {
            mServer.closeWriteTarget();
        }
        mRecvFinalized = true;
        mDiskExecutor.execute(() -> {
            DownloadEntity entity = finalizeReceivedFile(partFile, name, mime, bytes);
            mMainHandler.post(() -> {
                mReceivedEntity = entity;
                if (entity == null) {
                    if (listener != null) {
                        listener.onError("file", "finalize failed");
                    }
                } else if (listener != null) {
                    listener.onDone(role, bytes);
                }
            });
        });
    }

    @Nullable
    private DownloadEntity finalizeReceivedFile(File partFile, String name, String mime, long bytes) {
        if (partFile == null || partFile.length() != bytes) {
            Log.e(TAG, "finalize: size mismatch");
            return null;
        }
        // Re-uniquify at completion — a same-named download may have landed
        // during the transfer.
        File target = buildTargetFile(name, mime);
        if (!partFile.renameTo(target)) {
            Log.e(TAG, "finalize: rename failed");
            return null;
        }
        String path = target.getAbsolutePath();
        String resolvedMime = mime == null || mime.isEmpty()
                ? FileUriHelper.getMimeTypeFromFile(path) : mime;

        DownloadEntity entity = new DownloadEntity();
        entity.setId(ID_RANDOM.nextInt());
        entity.setFileType((FileUriHelper.isImage(resolvedMime) ? UrlType.IMAGE : UrlType.FILE).getValue());
        // Rendered as the row's "MIME · domain" meta line — a p2p:// pseudo
        // host names the transport honestly without claiming a web origin.
        entity.setFileUrl("p2p://" + deviceSlug());
        entity.setFileOriginUrl("");
        entity.setFileName(target.getName());
        entity.setFileImg(path);
        entity.setFilePath(path);
        entity.setFileMimeType(resolvedMime);
        entity.setFileProgress(100);
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileSize(bytes);
        entity.setFileStatus(Download.FINISHED);
        entity.setFileSafe(false);
        entity.setFileThumbnailUnavailable(false);
        mDownloadDataRepository.add(entity);
        GalleryPublisher.publish(mContext, path, resolvedMime);
        return entity;
    }

    private File buildTargetFile(String name, String mime) {
        // Mirror the download pipeline's naming (decode → sanitize → ensure
        // extension) so a P2P-received file is named identically to the same
        // file downloaded.
        String safeName = FileUriHelper.sanitizeFileName(FileUriHelper.decodeName(name));
        safeName = FileUriHelper.checkFileExtension(safeName, mime);
        File target = new File(StoragePaths.getDownloadPath(mContext), safeName);
        // Uniquify against BOTH disk AND the download table: a path free on
        // disk can still be owned by a queued/errored download row, and
        // colliding would let deleting that row destroy this file.
        while (target.exists()
                || mDownloadDataRepository.findByFilePath(target.getAbsolutePath()) != null) {
            target = new File(UrlParser.parseFilePath(target.getAbsolutePath()));
        }
        return target;
    }

    private static String deviceSlug() {
        String model = Build.MODEL == null ? "device" : Build.MODEL;
        String slug = model.toLowerCase(Locale.US).replaceAll("[^a-z0-9.-]+", "-");
        return slug.isEmpty() ? "device" : slug;
    }

    /* ── internals ──────────────────────────────────────────────────────── */

    private void ensureEngine(Runnable action) {
        if (mPort == null) {
            postError("engine", "engine not connected");
            return;
        }
        // A sticky-true mEngineRtcReady is NOT trustworthy when we had to
        // enable the pref for this session (the page's ICE stack may be dead
        // from a prior off→on cycle) — force a reload in that case.
        if (mEngineRtcReady && !mForceEngineReload) {
            action.run();
            return;
        }
        mPendingEngineAction = action;
        mEnsureRetries = 0;
        sendEnsure();
    }

    private void sendEnsure() {
        JSONObject probe = new JSONObject();
        try {
            probe.put("type", "ensure");
            // Force the page reload on the FIRST ensure of a temporarily-
            // enabled session; retries drop the force (the reload already
            // happened, we're only waiting for the pref to become visible).
            probe.put("force", mForceEngineReload && mEnsureRetries == 0);
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        postCommand(probe);
        // The timeout is the backstop if the reload/retry never yields an
        // RTC-ready page; re-armed on each ensure so retries get their time.
        mMainHandler.removeCallbacks(mEngineTimeout);
        mMainHandler.postDelayed(mEngineTimeout, ENGINE_READY_TIMEOUT_MS);
    }

    private void runPendingIfReady() {
        if (mEngineRtcReady && mPendingEngineAction != null) {
            mMainHandler.removeCallbacks(mEngineTimeout);
            Runnable action = mPendingEngineAction;
            mPendingEngineAction = null;
            action.run();
        }
    }

    private void onEngineTimeout() {
        if (mPendingEngineAction != null) {
            mPendingEngineAction = null;
            postError("engine", "engine unavailable");
        }
    }

    private void postCommand(JSONObject command) {
        if (mPort == null) {
            postError("engine", "engine not connected");
            return;
        }
        mPort.postMessage(command);
    }

    private void postError(String code, String detail) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "error: " + code + " " + detail);
        }
        Listener listener = mListener;
        stopSession(true);
        if (listener != null) {
            listener.onError(code, detail);
        }
    }

    private void stopSession(boolean deletePartial) {
        mMainHandler.removeCallbacks(mEngineTimeout);
        mPendingEngineAction = null;
        mAwaitingReload = false;
        mEnsureRetries = 0;
        mListener = null;
        mRole = null;
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        if (deletePartial && mRecvPartFile != null && !mRecvFinalized && mRecvPartFile.exists()) {
            if (!mRecvPartFile.delete()) {
                Log.e(TAG, "partial delete failed");
            }
        }
        mRecvPartFile = null;
        mRecvName = null;
        mRecvMime = null;
        mRecvFinalized = false;
        mTransferring = false;
        mReceivedEntity = null;
    }

    /**
     * True while bytes are actively moving (a progress event has fired and the
     * transfer hasn't completed) — used to confirm before a back-press
     * abandons an in-flight transfer.
     */
    @UiThread
    public boolean isTransferActive() {
        return mTransferring;
    }

    /** The finished download row from the last completed receive, or null. */
    @Nullable
    @UiThread
    public DownloadEntity getReceivedEntity() {
        return mReceivedEntity;
    }
}
