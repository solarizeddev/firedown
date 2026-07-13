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

    // Active session state. Main-thread only (all port + fragment traffic is
    // UI-thread), except the finalize step which hops to the disk executor.
    private Listener mListener;
    private String mRole; // "send" | "receive" | null
    private P2pLoopbackServer mServer;
    private String mRecvName;
    private long mRecvSize;
    private String mRecvMime;
    private File mRecvPartFile;
    private boolean mRecvFinalized;

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
                    // an ACTIVE transfer cannot survive the page going away.
                    if (mRole != null && mListener != null) {
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
            command.put("mime", entity.getFileMimeType());
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
            command.put("device", Build.MODEL);
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
        JSONObject command = new JSONObject();
        try {
            command.put("type", "stop");
            postCommand(command);
        } catch (JSONException e) {
            // Teardown proceeds regardless.
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
                mEngineRtcReady = json.optBoolean("rtc", false);
                runPendingIfReady();
            }
            case "ensure-result" -> {
                if (json.optBoolean("ok", false)) {
                    mEngineRtcReady = true;
                    runPendingIfReady();
                }
                // ok:false → the page is reloading; the fresh "ready" event
                // releases the pending action (or the timeout errors out).
            }
            case "code" -> {
                if (mListener != null) {
                    mListener.onCode(json.optString("role", ""), json.optString("code", ""));
                }
            }
            case "offer-parsed" -> {
                mRecvName = json.optString("name", "");
                mRecvSize = json.optLong("size", 0);
                mRecvMime = json.optString("mime", "");
                if (mListener != null) {
                    mListener.onOfferParsed(mRecvName, mRecvSize, mRecvMime, json.optString("device", ""));
                }
            }
            case "state" -> {
                if (mListener != null) {
                    mListener.onConnectionState(json.optString("state", ""));
                }
            }
            case "progress" -> {
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
        String safeName = FileUriHelper.sanitizeFileName(name);
        safeName = FileUriHelper.checkFileExtension(safeName, mime);
        File target = new File(StoragePaths.getDownloadPath(mContext), safeName);
        while (target.exists()) {
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
        if (mEngineRtcReady) {
            action.run();
            return;
        }
        mPendingEngineAction = action;
        JSONObject probe = new JSONObject();
        try {
            probe.put("type", "ensure");
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        postCommand(probe);
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
        mRecvSize = 0;
        mRecvFinalized = false;
    }
}
