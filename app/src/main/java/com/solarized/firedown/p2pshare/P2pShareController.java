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
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Java side of the P2P share engine — the bridge between the share screens
 * (P2pSendFragment / P2pReceiveFragment) and the WebRTC engine.
 *
 * <p><b>Why a hidden GeckoSession, not a background page.</b>
 * {@code RTCPeerConnection.createOffer()} HANGS FOREVER in a WebExtension
 * background page (it has no docShell / browsing context) but works exactly
 * like a normal tab in a real content document — proven on-device. So the
 * engine ({@code assets/p2pshare/engine-page.js}) runs as a page-world script
 * in a hidden {@link GeckoSession} that loads {@code http://127.0.0.1:<port>/engine},
 * served by this session's own {@link P2pLoopbackServer}. A thin bridge content
 * script ({@code content.js}) opens the native port and relays page↔Java. This
 * mirrors {@link PoTokenGenerator}'s hidden-session pattern.
 *
 * <p>Ownership split (mirrors PoTokenGenerator): this controller owns the
 * hidden engine {@link GeckoSession}, the native {@link WebExtension.Port}
 * (handed over by GeckoRuntimeHelper's onConnect once content.js connects),
 * the loopback byte server, and the post-transfer bookkeeping (verify + rename
 * the received file, insert the FINISHED download row). The page owns
 * RTCPeerConnection/DataChannel. The fragments own all UI and the
 * session-scoped WebRTC pref flip.
 *
 * <p>One session at a time by design — the share screens are modal
 * full-screen destinations, and the engine mirrors that with a single
 * session slot. Starting a new session tears down the previous one.
 *
 * <p><b>Readiness ordering.</b> RTCPeerConnection is pref-gated WebIDL
 * evaluated at page-global creation, so the fragment enables the WebRTC pref
 * and waits for it to APPLY before calling start. This controller then opens a
 * FRESH engine session, whose page therefore loads with the pref already on —
 * no reload dance needed (a fresh page-global sees the enabled pref). The
 * engine posts {@code {type:"ready", rtc:true}} once the page script is live;
 * the queued action runs on that event.
 */
@Singleton
public class P2pShareController {

    private static final String TAG = "P2pShareController";

    /**
     * Native port name — must match connectNative() in
     * assets/p2pshare/content.js. Port names reject hyphens.
     */
    public static final String PORT_NAME = "p2pshare";

    /**
     * Code prefixes — must match OFFER_PREFIX/ANSWER_PREFIX in
     * assets/p2pshare/engine-page.js. The UI uses them to validate a
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

    // Runtime + session registrar, wired by GeckoRuntimeHelper after runtime
    // creation (can't be @Inject'd — GeckoRuntimeHelper depends on this
    // controller, so the reverse would be a cycle; same setter pattern the
    // PoTokenGenerator uses via its constructor).
    private GeckoRuntime mRuntime;
    private Consumer<GeckoSession> mSessionRegistrar;

    // The hidden GeckoSession hosting the page-world WebRTC engine. Its
    // lifetime is one share session — recreated fresh each start so the page
    // loads with the just-enabled WebRTC pref (see the readiness note above).
    private GeckoSession mEngineSession;
    private WebExtension.Port mPort;
    private boolean mEngineReady;
    private Runnable mPendingEngineAction;
    private final Runnable mEngineTimeout = this::onEngineTimeout;

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
     * Wire the GeckoRuntime + session registrar. Called once by
     * GeckoRuntimeHelper right after the runtime is created (a setter, not
     * constructor injection, to avoid the Hilt dependency cycle — see the
     * field comment). {@code registrar} is {@code GeckoRuntimeHelper::registerSession},
     * which attaches the loaded WebExtensions' delegates to a session so the
     * p2pshare content script + native port bind to it.
     */
    @UiThread
    public void attachRuntime(@NonNull GeckoRuntime runtime,
                              @NonNull Consumer<GeckoSession> registrar) {
        mRuntime = runtime;
        mSessionRegistrar = registrar;
    }

    /* ── port lifecycle (called from GeckoRuntimeHelper) ────────────────── */

    @UiThread
    public void onPortConnected(@NonNull WebExtension.Port port) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "engine port connected");
        }
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
                    mEngineReady = false;
                    // The page went away. If WE closed the session (stopSession
                    // ran first, nulling mListener/mRole) this is the expected
                    // teardown and the guard below no-ops; otherwise the engine
                    // page crashed mid-share and the transfer can't survive it.
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
        if (!startLoopback()) {
            postError("engine", "loopback start failed");
            return;
        }
        mServer.setReadFile(path);

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
        ensureEngineSession(() -> postCommand(command));
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

        // The loopback must exist now to host the engine page; the write
        // target is armed later in acceptOffer() on this same server.
        if (!startLoopback()) {
            postError("engine", "loopback start failed");
            return;
        }

        JSONObject command = new JSONObject();
        try {
            command.put("type", "recv-start");
            command.put("code", code);
            command.put("stun", Preferences.getP2pStunServer(mSharedPreferences));
        } catch (JSONException e) {
            postError("engine", "command build failed");
            return;
        }
        ensureEngineSession(() -> postCommand(command));
    }

    /**
     * Receiver: user accepted the preview — create the target file, arm the
     * loopback write side, and let the engine answer.
     */
    @UiThread
    public void acceptOffer() {
        if (!"receive".equals(mRole) || mRecvName == null || mServer == null) {
            return;
        }
        // The target-file work (buildTargetFile → findByFilePath) hits Room,
        // which fatally rejects a main-thread query, plus disk I/O — so it MUST
        // run off the UI thread (same disk executor finalizeReceivedFile uses).
        // Snapshot the fields the disk task needs; the recv-accept command is
        // posted back on the main thread once the write side is armed.
        final String name = mRecvName;
        final String mime = mRecvMime;
        final P2pLoopbackServer server = mServer;
        mDiskExecutor.execute(() -> {
            final File partFile;
            try {
                StoragePaths.ensureDownloadPath(mContext);
                File target = buildTargetFile(name, mime);
                partFile = new File(target.getParentFile(), target.getName() + ".part");
                if (partFile.exists() && !partFile.delete()) {
                    throw new IOException("stale part file");
                }
                // Arm the write side on the SAME loopback that hosts the engine
                // page (started in startReceive) — no second server.
                server.setWriteTarget(partFile);
            } catch (IOException e) {
                mMainHandler.post(() -> postError("file", "cannot create target file"));
                return;
            }
            mMainHandler.post(() -> {
                // The session may have been torn down while we hopped threads
                // (Decline / back-press) — don't resurrect a dead one.
                if (!"receive".equals(mRole) || mServer != server) {
                    return;
                }
                mRecvPartFile = partFile;
                JSONObject command = new JSONObject();
                try {
                    command.put("type", "recv-accept");
                    command.put("writeUrl", server.getWriteUrl());
                } catch (JSONException e) {
                    postError("engine", "command build failed");
                    return;
                }
                postCommand(command);
            });
        });
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
                mEngineReady = json.optBoolean("rtc", false);
                if (mEngineReady) {
                    runPendingIfReady();
                } else {
                    // The page has a real docShell but no RTCPeerConnection —
                    // the WebRTC pref didn't apply before the page loaded.
                    // Nothing to retry (the fragment owns the pref); fail so the
                    // UI shows an error instead of hanging on "preparing".
                    postError("engine", "webrtc unavailable");
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

    /**
     * Start this session's loopback server (serves the engine page + read/write
     * byte endpoints). Called synchronously at session start so the engine-page
     * URL is available before the hidden GeckoSession is opened.
     */
    private boolean startLoopback() {
        try {
            mServer = new P2pLoopbackServer(mContext);
            mServer.start();
            return true;
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loopback start failed: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Ensure the hidden engine session is open and RTC-ready, then run
     * {@code action}. The session hosts the page-world WebRTC engine (see the
     * class doc); it's opened fresh per share so the page picks up the
     * just-enabled WebRTC pref. {@code action} runs once the engine posts a
     * {@code ready} event with {@code rtc:true}; a timeout backstops a page
     * that never comes up.
     */
    private void ensureEngineSession(Runnable action) {
        if (mRuntime == null || mSessionRegistrar == null) {
            postError("engine", "engine unavailable");
            return;
        }
        if (mServer == null) {
            postError("engine", "loopback not started");
            return;
        }
        if (mEngineReady && mPort != null) {
            action.run();
            return;
        }
        mPendingEngineAction = action;
        if (mEngineSession == null) {
            openEngineSession();
        }
        mMainHandler.removeCallbacks(mEngineTimeout);
        mMainHandler.postDelayed(mEngineTimeout, ENGINE_READY_TIMEOUT_MS);
    }

    /**
     * Open the hidden GeckoSession on the loopback engine page. Order mirrors
     * PoTokenGenerator: registerSession (attach the WebExtension delegates)
     * MUST run before open() so the p2pshare content script + native port bind
     * to this session when GeckoView's WebExtension subsystem wires it up.
     */
    private void openEngineSession() {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .allowJavascript(true)
                .build();
        GeckoSession session = new GeckoSession(settings);
        mEngineSession = session;
        mSessionRegistrar.accept(session);
        session.open(mRuntime);
        // Mark active so GeckoView treats it as a live tab for content-script
        // injection and doesn't background-throttle the engine's timers.
        session.setActive(true);
        session.loadUri(mServer.getEnginePageUrl());
    }

    private void closeEngineSession() {
        if (mEngineSession != null) {
            GeckoSession session = mEngineSession;
            mEngineSession = null;
            try {
                session.setActive(false);
                session.close();
            } catch (RuntimeException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "engine session close: " + e.getMessage());
                }
            }
        }
        mPort = null;
    }

    private void runPendingIfReady() {
        if (mEngineReady && mPendingEngineAction != null) {
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
        mEngineReady = false;
        // Null the listener/role BEFORE closing the session so the port's
        // onDisconnect (fired by close()) sees a torn-down session and doesn't
        // post a spurious "engine disconnected" error.
        mListener = null;
        mRole = null;
        closeEngineSession();
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
