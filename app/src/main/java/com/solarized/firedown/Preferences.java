package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.ContentBlocking;

import java.io.File;


public class Preferences {

    private static final String TAG = Preferences.class.getSimpleName();

    public static final String UPDATE_APK = "firedown.apk";

    /**
     * Single source of truth for the downloaded-update APK location. It lives
     * in app-specific EXTERNAL files (getExternalFilesDir) — not getFilesDir —
     * because DownloadManager cannot write to internal app storage, and the
     * APK download now goes through DownloadManager (so it survives the app
     * process being evicted mid-download). App-specific external storage needs
     * no runtime permission. Falls back to internal storage only on the rare
     * device with no external volume mounted (DownloadManager is skipped
     * there — see UpdateWorker). The path matches
     * DownloadManager.Request.setDestinationInExternalFilesDir(ctx, null,
     * UPDATE_APK) so the receiver reads exactly what was written.
     */
    public static File getUpdateApkFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return new File(dir, UPDATE_APK);
    }

    /**
     * Primary update-check endpoint. Sits behind Cloudflare. UpdateWorker
     * tries each URL in {@link #UPDATE_URL_FALLBACKS} until one succeeds.
     */
    public static final String UPDATE_URL = "https://www.firedown.app/status.json";

    /**
     * Fallback endpoints, tried in order after {@link #UPDATE_URL} fails.
     *
     * Spain's LaLiga court orders force major ISPs to block large blocks
     * of Cloudflare IPs during match windows (the Cloudflare-front blast
     * radius affects every Cloudflare-hosted service whether or not it's
     * related to piracy). The primary firedown.app endpoint becomes
     * unreachable for those users — TCP SYN is dropped, DNS-over-HTTPS
     * can't help because the block is IP-level, not DNS-level.
     *
     * The GitHub Raw mirror is hosted on Microsoft Azure IPs, not on
     * Cloudflare's, so the block doesn't catch it. The file is a
     * straight copy of status.json committed to the repo's main branch
     * — keep it updated alongside the firedown.app one.
     */
    public static final String[] UPDATE_URL_FALLBACKS = new String[]{
            UPDATE_URL,
            "https://raw.githubusercontent.com/solarizeddev/firedown/main/status.json",
    };

    public static final int EXTRA_TOUCH_AREA_DP = 12;

    public static final String CLIPBOARD_LABEL = "com.solarized.firedown.clipboard.label";

    // One-shot "you may need to play the embedded media first" banner on the
    // Capture sheet. Set once the user taps or dismisses it, so it never returns
    // (the toolbar Help item remains the permanent affordance).
    public static final String CAPTURE_HELP_BANNER_DISMISSED = "com.solarized.firedown.preferences.capture.help.banner.dismissed";

    public static final String SORT_LOCAL = "com.solarized.firedown.preferences.sort.local";

    public static final String SORT_LIST = "com.solarized.firedown.preferences.sort.list";

    public static final String SORT_TABS_LIST = "com.solarized.firedown.preferences.sort.tabs.list";

    public static final String SORT_DOWNLOADS_LIST = "com.solarized.firedown.preferences.sort.downloads.list";

    public static final String SORT_VAULT_LIST = "com.solarized.firedown.preferences.sort.vault.list";

    // ---- bookmarks sync (docs/BOOKMARKS_SYNC.md) ----
    /** Whether bookmark sync is enabled (off by default; user opts in). */
    public static final String SYNC_ENABLED = "com.solarized.firedown.preferences.sync.enabled";
    /** The hosted backend base URL. Bookmark sync is pinned to this — there is no
     *  BYO-backend picker (a configurable/self-hosted backend is a downloads-vault
     *  concern, not a free-bookmarks one). See {@code SyncManager#backendUrl}. */
    public static final String SYNC_DEFAULT_BACKEND = "https://api.firedown.app";
    /** The hosted storage (encrypted downloads-vault) backend base URL. Distinct
     *  service from bookmark sync, same recovery-code-derived identity. Unlike
     *  bookmarks this IS a paid/vault concern, so a BYO-backend picker is allowed
     *  here later; this is just the default. */
    public static final String STORAGE_DEFAULT_BACKEND = "https://storage.firedown.app";
    /** The payments-mint backend base URL (mint.firedown.app). Issues blind-signed
     *  storage credits; UNAUTHENTICATED (the mint never sees the account — that
     *  unlinkability is the point). Same default-constant shape as
     *  {@link #STORAGE_DEFAULT_BACKEND}. */
    public static final String MINT_DEFAULT_BACKEND = "https://mint.firedown.app";
    /** Epoch millis of the last successful sync (0 = never). */
    public static final String SYNC_LAST_SYNCED_AT = "com.solarized.firedown.preferences.sync.last.synced.at";
    /** Last server document version observed (status display). */
    public static final String SYNC_LAST_VERSION = "com.solarized.firedown.preferences.sync.last.version";
    /** True when the last terminal sync was a (non-transient) failure — drives
     *  the toolbar indicator's error state. Cleared on the next success. */
    public static final String SYNC_LAST_ERROR = "com.solarized.firedown.preferences.sync.last.error";
    /** True once the Bookmarks "sync is available" announce banner has been
     *  dismissed (or sync was enabled) — retires it permanently. */
    public static final String SYNC_BANNER_DISMISSED = "com.solarized.firedown.preferences.sync.banner.dismissed";
    /** Settings entry that opens the bookmarks-sync screen. */
    public static final String SETTINGS_SYNC = "com.solarized.firedown.preferences.settings.sync";
    /** Sync screen actions (clickable preferences). */
    public static final String SETTINGS_SYNC_SHOW_CODE = "com.solarized.firedown.preferences.sync.show.code";
    /** Exports the recovery code to a user-chosen text file (SAF). */
    public static final String SETTINGS_SYNC_RESTORE = "com.solarized.firedown.preferences.sync.restore";
    /** Opens the offline "How sync encryption works" FAQ dialog. */
    public static final String SETTINGS_SYNC_HELP = "com.solarized.firedown.preferences.sync.help";
    /** "I have a recovery code" — link this device to an existing account (restore
     *  downloads backup + storage credit). Shown only when no code exists yet. */
    public static final String SETTINGS_SYNC_LINK_CODE = "com.solarized.firedown.preferences.sync.link.code";
    /** Right-to-erasure: delete the encrypted document from the server. */
    public static final String SETTINGS_SYNC_DELETE_DATA = "com.solarized.firedown.preferences.sync.delete.data";
    // (No category-header keys: the Cloud screen is header-FREE — the last
    // header, Bookmarks, umbrella'd every row after it as its children, and
    // every category on the merged screen had collapsed to a single child.)

    // ---- Cloud Backup (encrypted downloads → storage.firedown.app) ----
    // A SEPARATE feature from the local "Safe Folder" (file_safe / files/safe/),
    // which never leaves the device. Cloud Backup deliberately DOES leave the
    // device, end-to-end encrypted, sharing the bookmark-sync recovery code
    // (one account across services). User-facing copy says "Cloud Backup",
    // never "vault" — that word is the local Safe Folder.
    /** Set once the user has set up Cloud Backup / has data on the server. Unlike
     *  bookmark sync there is no on/off switch (it is action-driven — backing up a
     *  download sets it up on demand); this flag exists so disabling bookmark sync
     *  does NOT wipe the shared recovery code while Cloud Backup still needs it
     *  (and vice-versa). Cleared by the loadStatus reconcile when the server shows a dead account. */
    public static final String CLOUD_BACKUP_ENABLED = "com.solarized.firedown.preferences.cloud.backup.enabled";
    /** True once the Downloads "back up your downloads" announce banner has been
     *  dismissed (or Cloud Backup got set up) — retires it permanently. The twin
     *  of {@link #SYNC_BANNER_DISMISSED}, which does the same for the Bookmarks
     *  list's sync banner. */
    public static final String CLOUD_BACKUP_BANNER_DISMISSED = "com.solarized.firedown.preferences.cloud.backup.banner.dismissed";
    /** The ACCUMULATED purchased storage plan (size cap in GB × coverage months),
     *  stored CLIENT-side and MERGED at each redeem success (BuyCreditViewModel:
     *  size = largest bought, months = summed GB-months at that size — mirroring
     *  the server's summing balance) — the server deliberately only knows the
     *  anonymous GB-month balance, so this is the only place the status hero can
     *  learn "Up to 50 GB · 1 year" and size its month-tick runway. 0 = unknown
     *  (legacy denomination purchase, or a recovery-code restore on a new device)
     *  → the hero falls back to the raw balance + a tickless covered-until line.
     *  Survives "Delete backed-up files" (the balance does too). */
    public static final String CLOUD_PLAN_SIZE_GB = "com.solarized.firedown.preferences.cloud.plan.size.gb";
    public static final String CLOUD_PLAN_DURATION_MONTHS = "com.solarized.firedown.preferences.cloud.plan.duration.months";
    /** CACHE, not a setting: the last successfully-read total bytes held in Cloud
     *  Backup (-1 = unknown). The in-memory {@code lastStatus()} snapshot dies
     *  with the process, so on a COLD start the home backup pill had nothing to
     *  paint and popped in a second later, under the wordmark. This survives the
     *  process so the resting figure is on screen immediately.
     *
     *  <p><b>Only the TOTAL is persisted — never the quota.</b> The quota drives
     *  the ALARM (read-only grace → the "Backup paused" card and its countdown);
     *  restoring that from disk could tell a user their files are about to be
     *  deleted on an account that has since topped up. Alarms stay
     *  evidence-based, read fresh from the server every time. A lifetime total
     *  is the opposite: it changes slowly and is superseded the moment the pull
     *  lands, so a slightly stale one costs nothing.
     *
     *  <p>Cleared wherever the data it describes can vanish — "Delete backed-up
     *  files" and the loadStatus dead-account reconcile. That clearing is what
     *  makes reading it back safe. */
    public static final String CLOUD_LAST_TOTAL_BYTES = "com.solarized.firedown.preferences.cloud.last.total.bytes";
    /** Whether the home screen may show the backup pill/card at all. DEFAULT
     *  TRUE — a DISPLAY preference, not a kill switch: it gates only
     *  {@code HomeFragment.applyBackupPill} and changes no cloud state, so it
     *  can never hide the Backups row, the Downloads-overflow routing, or a
     *  paid balance. That distinction is the whole point — the obvious
     *  "disable Cloud Backup" toggle would have to clear
     *  {@link #CLOUD_BACKUP_ENABLED}, which gates the user's own doors to
     *  files still sitting on the server beside their credit; hiding those is
     *  exactly the stranding {@code deleteAllData} is written to avoid.
     *
     *  <p>It DOES cover the read-only grace card as well as the calm states.
     *  A control labelled "show backup status on home" that still painted a
     *  card would be lying, and the grace deadline is reported on the Cloud
     *  screen and the Backups list header regardless — home is a convenience
     *  channel for it, not the only one. */
    public static final String SETTINGS_CLOUD_HOME_STATUS = "com.solarized.firedown.preferences.settings.cloud.home.status";
    /** Settings entry that opens the Cloud Backup screen. */
    public static final String SETTINGS_CLOUD_BACKUP = "com.solarized.firedown.preferences.settings.cloud.backup";
    /** Cloud Backup screen actions (clickable preferences). */
    public static final String SETTINGS_CLOUD_BACKUP_STATUS = "com.solarized.firedown.preferences.cloud.backup.status";
    public static final String SETTINGS_CLOUD_BACKUP_FILES = "com.solarized.firedown.preferences.cloud.backup.files";
    /** Click-row: scan a browser pairing QR (opens the web client without the
     *  user typing their recovery code into a web page). */
    public static final String SETTINGS_CLOUD_BACKUP_PAIR = "com.solarized.firedown.preferences.cloud.backup.pair";
    /** Opens the "Add storage credit" purchase flow (metered-balance top-up). */
    public static final String SETTINGS_CLOUD_BACKUP_BUY = "com.solarized.firedown.preferences.cloud.backup.buy";
    public static final String SETTINGS_CLOUD_BACKUP_SHOW_CODE = "com.solarized.firedown.preferences.cloud.backup.show.code";
    public static final String SETTINGS_CLOUD_BACKUP_DELETE_DATA = "com.solarized.firedown.preferences.cloud.backup.delete.data";
    public static final String SETTINGS_CLOUD_BACKUP_HELP = "com.solarized.firedown.preferences.cloud.backup.help";

    /**
     * Bookmarks-list sort order toggle: false (default) = recency
     * (file_date DESC, the historical order), true = A–Z by title.
     * Governs only the unfiltered list — search results stay
     * recency-ordered.
     */
    public static final String SORT_BOOKMARKS_ALPHA = "com.solarized.firedown.preferences.sort.bookmarks.alpha";

    public static final String SETTINGS_AUTOFILL = "com.solarized.firedown.preferences.browser.password";

    public static final String SETTINGS_BLOCK_LOCATION = "com.solarized.firedown.preferences.browser.block.location";

    public static final boolean DEFAULT_BLOCK_LOCATION = true;

    /**
     * Auto-block sites' "open in app" / "install our app" redirects without
     * prompting. Covers both Play Store install nags (play.google.com /
     * market://) and generic app deeplinks (tiktok://, intent://, …). When ON
     * (the default), NavigationDelegate's denial is taken silently (with a
     * Snackbar that carries a one-shot "Open") for any PAGE-INITIATED redirect
     * (autoRedirect = !isDirectNavigation). A deliberately typed/bookmarked
     * deeplink (isDirectNavigation) still prompts, and user comms schemes
     * (mailto:/tel:/sms:/geo:) are never blocked. (An earlier wasRedirector-only
     * gate was too narrow — it missed TikTok, whose deeplink fires on a
     * first/cached view with no back-entry — so the dialog leaked through.)
     * When OFF: a Play Store redirect just loads the listing in-browser (no
     * prompt — it stays in the browser), and a generic app deeplink shows the
     * BrowserAppDialogFragment "open in another app" dialog.
     *
     * Default ON: app-install/open nags are near-universally unwanted, matching
     * the app's other hardened defaults (HTTPS-only, disk cache off).
     *
     * The key value keeps the legacy …block.playstore.redirects name on purpose.
     * Changing the DEFAULT (false→true) needs no new key here: there's no
     * semantic inversion (true still means "block"), and the app never persists
     * defaults (no PreferenceManager.setDefaultValues), so an untouched install
     * reads the new default while a user who explicitly toggled keeps their
     * stored choice. (The new-key rule applies to enable→disable *inversions*,
     * which this isn't.)
     */
    public static final String SETTINGS_BLOCK_APP_REDIRECTS =
            "com.solarized.firedown.preferences.browser.block.playstore.redirects";

    public static final boolean DEFAULT_BLOCK_APP_REDIRECTS = true;

    public static final String SETTINGS_THEME = "com.solarized.firedown.preferences.theme";

    public static final String SETTINGS_THEME_DEFAULT = "com.solarized.firedown.preferences.theme.default";

    public static final String SETTINGS_THEME_DARK = "com.solarized.firedown.preferences.theme.dark";

    public static final String SETTINGS_THEME_LIGHT = "com.solarized.firedown.preferences.theme.light";

    public static final String SETTINGS_THEME_OLED = "com.solarized.firedown.preferences.theme.oled";

    /**
     * Sentinel value stored in {@link #SETTINGS_THEME} when the user picks
     * AMOLED mode. Distinct from {@link androidx.appcompat.app.AppCompatDelegate}
     * MODE_NIGHT_* constants (which are -1, 1, 2, 3) so it survives a
     * round-trip through getInt without colliding. App.onCreate translates
     * this to MODE_NIGHT_YES + the OLED theme overlay.
     */
    public static final int THEME_OLED = -100;

    /**
     * JavaScript JIT is ENABLED by default — turning it off globally noticeably
     * degrades complex sites, so it belongs with the other advanced "harden at a
     * cost" toggles in the Security section rather than being something a normal
     * user has to discover and switch on. This is a "Disable JIT" switch
     * (default OFF = JIT enabled), mirroring the SETTINGS_DISABLE_WEBGL /
     * SETTINGS_DISABLE_WASM convention. {@link
     * com.solarized.firedown.geckoview.GeckoRuntimeHelper#setJITCompiler}
     * receives the inverted value.
     *
     * <p>Deliberately a NEW key: the previous SETTINGS_ENABLE_JIT
     * (default-disabled, opt-in) value must not carry over on update, or users
     * who never touched it would read back {@code false} and stay on the old
     * JIT-disabled baseline under the new default-enabled semantics.</p>
     */
    public static final String SETTINGS_DISABLE_JIT = "com.solarized.firedown.preferences.browser.disable.jit";

    public static final boolean DEFAULT_DISABLE_JIT = false;

    public static final String SETTINGS_DISABLE_WEBGL = "com.solarized.firedown.preferences.browser.disable.webgl";

    public static final boolean DEFAULT_DISABLE_WEBGL = false;

    public static final String SETTINGS_ENABLE_RESIST_FINGERPRINTING = "com.solarized.firedown.preferences.browser.enable.resist.fingerprinting";

    public static final boolean DEFAULT_RESIST_FINGERPRINTING = false;

    /**
     * Spoof the timezone to UTC (anti-fingerprinting). Default OFF — UTC clocks
     * confuse calendar/scheduling sites, so it is a deliberate opt-in for the
     * privacy-conscious, like {@link #SETTINGS_ENABLE_RESIST_FINGERPRINTING}.
     * Unlike global Resist Fingerprinting this flips ONLY the JSDateTimeUTC
     * fingerprinting-protection target (FPP is already enabled at runtime), so it
     * does not degrade the rest of the page or media capture. Applied by {@link
     * com.solarized.firedown.geckoview.GeckoRuntimeHelper#setTimezoneSpoofing},
     * which adds/clears "+JSDateTimeUTC" on the global
     * privacy.fingerprintingProtection.overrides pref (independent of the
     * per-site granularOverrides pref).
     */
    public static final String SETTINGS_SPOOF_TIMEZONE = "com.solarized.firedown.preferences.browser.spoof.timezone";

    public static final boolean DEFAULT_SPOOF_TIMEZONE = false;

    // NOTE: the "Enable WebRTC" toggle (…browser.enable.webrtc, default OFF)
    // was REMOVED — WebRTC is now always on, the stock-Firefox posture. The
    // classic local-IP leak is already covered by mDNS candidate obfuscation
    // (kept ON for browsing, flipped only inside a P2P share session), and the
    // off-default broke real sites (Meet/Discord/WhatsApp calls) while the P2P
    // share had to pref-dance around it. If a disable-style switch is ever
    // reintroduced, it needs a NEW key (the JIT/WASM inversion rule).

    /**
     * STUN server used by the P2P direct-share connection (the "what's my
     * public address" echo — the only external party a share ever contacts,
     * and only when the two devices are on different networks). ONE url is
     * ever handed to the engine: listing several would make ICE query them
     * all in parallel, leaking the user's IP to every fallback on every
     * share. The value is either a full stun: url from the settings list or
     * {@link #P2P_STUN_CUSTOM_VALUE}, in which case the url typed by the
     * user lives in {@link #SETTINGS_P2P_STUN_CUSTOM}. Resolve through
     * {@link #getP2pStunServer}.
     */
    /** Root-settings DOOR to the Direct share sub-screen (DirectShareFragment). */
    public static final String SETTINGS_P2P_SCREEN = "com.solarized.firedown.preferences.p2p.screen";
    /** Root-settings DOOR to the Security sub-screen (SecurityFragment). */
    public static final String SETTINGS_SECURITY_SCREEN = "com.solarized.firedown.preferences.security.screen";
    public static final String SETTINGS_P2P_STUN = "com.solarized.firedown.preferences.p2p.stun.server";

    public static final String DEFAULT_P2P_STUN = "stun:stun.cloudflare.com:3478";

    public static final String SETTINGS_P2P_STUN_CUSTOM = "com.solarized.firedown.preferences.p2p.stun.custom";

    public static final String P2P_STUN_CUSTOM_VALUE = "custom";

    public static String getP2pStunServer(SharedPreferences sharedPreferences) {
        String value = sharedPreferences.getString(SETTINGS_P2P_STUN, DEFAULT_P2P_STUN);
        if (P2P_STUN_CUSTOM_VALUE.equals(value)) {
            String custom = sharedPreferences.getString(SETTINGS_P2P_STUN_CUSTOM, "");
            // An empty custom entry falls back to the default rather than
            // silently downgrading every share to host-candidates-only.
            if (custom == null || custom.trim().isEmpty()) {
                return DEFAULT_P2P_STUN;
            }
            return custom.trim();
        }
        return value;
    }

    /**
     * OPTIONAL TURN relay for the P2P share — the ONLY way two peers that
     * can't reach each other directly (both on mobile data / CGNAT, or one
     * behind a full-tunnel VPN) can connect: the relay forwards the (still
     * E2E-encrypted) DataChannel. Default is EMPTY = no relay (the privacy
     * default — a share then contacts nothing but the STUN echo, and a
     * truly-unreachable pair fails honestly). When set, ICE only actually
     * relays through it if a direct path fails. Unlike STUN there is no shipped
     * list: TURN needs credentials, so it's a user-entered url + username +
     * credential (typically the user's own coturn). {@code turn:}/{@code turns:}
     * scheme is validated in the settings editor.
     */
    public static final String SETTINGS_P2P_TURN_URL = "com.solarized.firedown.preferences.p2p.turn.url";
    public static final String SETTINGS_P2P_TURN_USER = "com.solarized.firedown.preferences.p2p.turn.user";
    public static final String SETTINGS_P2P_TURN_CRED = "com.solarized.firedown.preferences.p2p.turn.cred";

    // NOTE: there is deliberately NO baked-in TURN credential. A static
    // credential shipped in the (open-source) app would hand the relay to the
    // whole internet as an open proxy. The first-party Firedown relay is FREE
    // and on by default, but its credentials are FETCHED per share session —
    // short-lived coturn REST creds from the endpoint below (anonymous GET, no
    // identifiers; server half in firedown-api handler_relay.go, coturn abuse
    // rails in deploy/turn-provision.sh). Fetch failure degrades to
    // direct+STUN-only. The user-configured TURN above remains as an ADDITIONAL
    // relay for networks the default can't cross.

    /** TURN credential minting endpoint for the P2P share's free relay
     *  fallback — same host the bookmark sync uses. Returns
     *  {@code {urls[], username, credential, ttl_seconds}} (coturn REST API);
     *  404 while the server side isn't deployed, which the controller treats
     *  as "no relay" and proceeds without one. */
    public static final String P2P_RELAY_CREDS_URL = SYNC_DEFAULT_BACKEND + "/v1/relay/creds";

    /** PoW challenge endpoint gating the relay-creds mint (anti-abuse): the
     *  client fetches {@code {challenge, pow_bits}}, solves the hashcash, and
     *  passes challenge+nonce to {@link #P2P_RELAY_CREDS_URL}. 404 when relay
     *  or PoW is off → the client skips straight to a relay-less share. */
    public static final String P2P_RELAY_CHALLENGE_URL = SYNC_DEFAULT_BACKEND + "/v1/relay/challenge";

    /** P2P answer-rendezvous BASE — the one-time mailbox that removes the
     *  reply step from a cross-network share. The sender mints an id, embeds
     *  {@code <base>/a/<id>} in the offer and long-polls {@code …?wait=1};
     *  the receiver POSTs its answer there. Self-contained offer (never
     *  uploaded); the LAN answer return is tried first, this second, the
     *  reply link/QR only if this is unreachable. Server half:
     *  firedown-api handler_rendezvous.go. */
    public static final String P2P_RENDEZVOUS_URL = SYNC_DEFAULT_BACKEND + "/v1/p2p";

    /** A configured TURN relay, or {@code null} when none is set. */
    public static final class P2pTurn {
        public final String url;
        public final String username;
        public final String credential;

        public P2pTurn(String url, String username, String credential) {
            this.url = url;
            this.username = username;
            this.credential = credential;
        }
    }

    /**
     * Signaling relay origin for the P2P share (see {@code signaling/server.js}),
     * a BUILD-TIME constant — deliberately NOT a settings row (a niche knob;
     * the "Link relay" preference + editor were removed to keep Direct share
     * settings to the two rows people actually use). EMPTY = serverless, the
     * shipping state: a shared link completes on the same network (LAN answer
     * return) and cross-network uses the reply code; the built-in TURN relay
     * covers the hard NETWORKS either way (signaling and media relay are
     * orthogonal). If a first-party relay (e.g. {@code https://sig.firedown.app})
     * is ever deployed, set it here and the whole one-link flow — controller
     * plumbing, deep-link routing — comes alive unchanged.
     */
    private static final String P2P_SIGNALING_DEFAULT = "";

    /** The signaling relay origin (no trailing slash), or empty = serverless. */
    @NonNull
    public static String getP2pSignalingUrl(SharedPreferences sharedPreferences) {
        return P2P_SIGNALING_DEFAULT;
    }

    @Nullable
    public static P2pTurn getP2pTurn(SharedPreferences sharedPreferences) {
        String url = sharedPreferences.getString(SETTINGS_P2P_TURN_URL, "");
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        return new P2pTurn(
                url.trim(),
                sharedPreferences.getString(SETTINGS_P2P_TURN_USER, "").trim(),
                sharedPreferences.getString(SETTINGS_P2P_TURN_CRED, "").trim());
    }

    /**
     * WebAssembly is ENABLED by default — disabling it globally broke sites
     * that hard-require WASM (x.com login, kick.com) with no obvious recovery.
     * This is a "Disable WebAssembly" switch (default OFF = WASM enabled),
     * mirroring the SETTINGS_DISABLE_WEBGL convention. The per-site allowlist
     * acts as exceptions that keep WASM on when this is turned ON.
     *
     * <p>Deliberately a NEW key: the previous SETTINGS_ENABLE_WEBASSEMBLY
     * (default-disabled) value must not carry over on update, or users who
     * never touched it would stay on the old disabled baseline.</p>
     */
    public static final String SETTINGS_DISABLE_WASM = "com.solarized.firedown.preferences.browser.disable.webassembly";

    public static final boolean DEFAULT_DISABLE_WASM = false;

    /** Click key for the WASM settings sub-screen entry. */
    public static final String SETTINGS_WASM = "com.solarized.firedown.preferences.browser.wasm";

    public static final String SETTINGS_ENABLE_DRM = "com.solarized.firedown.preferences.browser.enable.drm";

    /**
     * HTTPS-only mode — refuse plaintext HTTP loads, show a warning page
     * with a per-site override option. Default ON: the privacy gain is
     * substantial and modern sites are nearly all HTTPS; the warning page
     * makes the rare HTTP-only site one click to allow.
     */
    public static final String SETTINGS_HTTPS_ONLY = "com.solarized.firedown.preferences.browser.https.only";
    public static final boolean DEFAULT_HTTPS_ONLY = true;

    /**
     * Disk cache toggle. Off = on-disk caching (default), On = in-memory
     * cache only. Disabling defeats cross-site cache fingerprinting and
     * leaves no cached content on disk to recover, at the cost of slower
     * repeat visits. Default OFF (= disk cache enabled) since the perf
     * cost is the more visible of the two effects. Naming follows the
     * "Disable X" convention used by SETTINGS_DISABLE_WEBGL — the switch
     * ON means the privacy-preferring action.
     */
    public static final String SETTINGS_DISABLE_DISK_CACHE = "com.solarized.firedown.preferences.browser.disable.disk.cache";
    public static final boolean DEFAULT_DISABLE_DISK_CACHE = false;

    /**
     * Disable Google Safe Browsing — its blocklist of malware / phishing
     * URLs sends URL hash prefixes to Google for matching. The switch ON
     * stops those network calls but loses warnings on known-bad sites.
     * Default OFF (= Safe Browsing on) because the security benefit is
     * concrete and the privacy leak is hash-prefixes not full URLs.
     * Naming follows the "Disable X" convention.
     */
    public static final String SETTINGS_DISABLE_SAFE_BROWSING = "com.solarized.firedown.preferences.browser.disable.safebrowsing";
    public static final boolean DEFAULT_DISABLE_SAFE_BROWSING = false;

    public static final String SETTINGS_ANTI_TRACKING = "com.solarized.firedown.preferences.browser.tracking";

    public static final String SETTINGS_ANTI_TRACKING_DEFAULT = "com.solarized.firedown.preferences.browser.tracking.default";

    public static final String SETTINGS_ANTI_TRACKING_STRICT = "com.solarized.firedown.preferences.browser.tracking.strict";

    public static final String SETTINGS_ANTI_TRACKING_CUSTOM = "com.solarized.firedown.preferences.browser.tracking.custom";

    public static final String SETTINGS_ANTI_TRACKING_STRIP_LIST = "com.solarized.firedown.preferences.browser.tracking.strip.list";

    public static final String SETTINGS_ANTI_TRACKING_USER_PARAMS = "com.solarized.firedown.preferences.browser.tracking.strip.user.params";

    // Default tracking query parameter strip list. Whitespace-separated.
    // Union of Brave's curated kSimpleQueryStringTrackers
    // (brave-core/components/query_filter/browser/utils.cc), Firefox's
    // privacy.query_stripping.strip_list defaults, and standard utm_*
    // campaign parameters. Conditional / host-scoped entries (mkt_tok,
    // igsh on instagram, si on youtube, ...) are intentionally excluded
    // — GeckoView's flat strip list cannot evaluate URL context, and
    // stripping them globally would break legitimate flows like
    // unsubscribe links.
    public static final String DEFAULT_QUERY_STRIP_LIST =
            "_kx _openstat at_recipient_id at_recipient_list bbeml bsft_clkid bsft_uid " +
                    "dclid epik et_rid fb_action_ids fb_comment_id fbclid gbraid gclid " +
                    "guce_referrer guce_referrer_sig hsCtaTracking igshid irclickid mc_cid mc_eid " +
                    "mkcid mkevt mkwid ml_subscriber ml_subscriber_hash msclkid mtm_cid oft_c " +
                    "oft_ck oft_d oft_id oft_ids oft_k oft_lk oft_sk oly_anon_id oly_enc_id pcrid " +
                    "pk_cid rb_clickid s_cid s_kwcid sc_customer sc_eh sc_uid sfmc_activityid " +
                    "sfmc_id sms_click sms_source sms_uph srsltid ss_email_id syclid ttclid " +
                    "twclid unicorn_click_id utm_campaign utm_content utm_medium utm_source " +
                    "utm_term vero_conv vero_id vgo_ee wbraid wickedid yclid ymclid ysclid";

    public static final String SETTINGS_CLEAR_DATA = "com.solarized.firedown.preferences.browser.clear";

    public static final String SETTINGS_DOWNLOADS = "com.solarized.firedown.preferences.downloads.location";

    public static final String SETTINGS_RESTORE_DOWNLOADS = "com.solarized.firedown.preferences.downloads.restore";

    public static final String SETTINGS_SAVE_ASK = "com.solarized.firedown.preferences.downloads.save.ask";

    public static final String SETTINGS_GALLERY = "com.solarized.firedown.preferences.downloads.gallery";

    public static final boolean DEFAULT_SETTINGS_SAVE_ASK = false;

    public static final String SETTINGS_DOH = "com.solarized.firedown.preferences.browser.doh";

    public static final String SETTINGS_DOH_SWITCH = "com.solarized.firedown.preferences.browser.doh.switch";

    public static final String SETTINGS_DOH_PREF = "com.solarized.firedown.preferences.browser.doh.pref";

    public static final String SETTINGS_DOH_CUSTOM = "com.solarized.firedown.preferences.browser.doh.custom";

    /**
     * Sentinel SETTINGS_DOH value meaning "use the user-entered URL in
     * SETTINGS_DOH_CUSTOM". Every other SETTINGS_DOH value is itself a DoH
     * endpoint URL (see @array/settings_doh_servers). Must match the
     * trailing entryValue in that array.
     */
    public static final String SETTINGS_DOH_CUSTOM_VALUE = "custom";

    public static final String SETTINGS_TABS = "com.solarized.firedown.preferences.browser.tabs";
    public static final String SETTINGS_QUIT = "com.solarized.firedown.preferences.browser.quit";

    public static final String SETTINGS_QUIT_PREF = "com.solarized.firedown.preferences.browser.quit.pref";

    public static final String SETTINGS_QUIT_PREF_TABS = "com.solarized.firedown.preferences.browser.quit.pref.tabs";

    public static final String SETTINGS_QUIT_PREF_HISTORY = "com.solarized.firedown.preferences.browser.quit.pref.history";

    public static final String SETTINGS_QUIT_PREF_COOKIES = "com.solarized.firedown.preferences.browser.quit.pref.cookies";

    public static final String SETTINGS_QUIT_PREF_CACHE = "com.solarized.firedown.preferences.browser.quit.pref.cache";

    public static final String SETTINGS_COOKIES = "com.solarized.firedown.preferences.browser.cookies";

    // Default DoH endpoint when the toggle is enabled without an explicit
    // pick. Must match the first entry of @array/settings_doh_servers.
    // (DoH is gated by SETTINGS_DOH_SWITCH, off by default, so this only
    // takes effect once the user turns DoH on.)
    public static final String DEFAULT_SETTINGS_DOH = "https://dns.mullvad.net/dns-query";

    public static final String DEFAULT_SETTINGS_COOKIES = String.valueOf(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS);

    public static final String SETTINGS_VERSION = "com.solarized.firedown.preferences.about.version";

    public static final String SETTINGS_GECKO = "com.solarized.firedown.preferences.about.gecko";

    public static final String SETTINGS_CONTACT = "com.solarized.firedown.preferences.about.contact";

    public static final String SETTINGS_WEBSITE = "com.solarized.firedown.preferences.about.website";

    public static final String SETTINGS_BLOCK_JAVASCRIPT = "com.solarized.firedown.preferences.browser.block.javascript";

    public static final String SETTINGS_APP_LOCK_MAIN = "com.solarized.firedown.preferences.lock.main";

    public static final String SETTINGS_APP_LOCK = "com.solarized.firedown.preferences.lock";

    public static final String SETTINGS_APP_LOCK_TIME = "com.solarized.firedown.preferences.lock.time";

    public static final String SETTINGS_APP_LOCK_UPDATE_TIME = "com.solarized.firedown.preferences.lock.update.time";

    public static final String SETTINGS_APP_LOCK_REQUIRED = "com.solarized.firedown.preferences.lock.required";

    public static final String SETTINGS_DONATE = "com.solarized.firedown.preferences.donate";

    /** Click-row → {@code ShareAppFragment} (QR + share-sheet link). */
    public static final String SETTINGS_SHARE_APP = "com.solarized.firedown.preferences.share.app";

    public static final String SETTINGS_SEARCH_ENGINE = "com.solarized.firedown.preferences.search.engine";

    /**
     * User-defined ("custom") search engine, Firefox-style: a display name,
     * a search-URL template with {@code %s} in place of the query, and an
     * optional suggestions-URL template (OpenSearch JSON). The templates are
     * validated at save time (SearchFragment) to contain exactly the
     * {@code %s} placeholder and no other {@code %} — both
     * {@code String.format} (search) and {@code URLUtil.composeSearchUrl}
     * (suggestions) consume them, and a stray {@code %} would throw in the
     * former while surviving the latter.
     */
    public static final String SETTINGS_SEARCH_ENGINE_CUSTOM = "com.solarized.firedown.preferences.search.engine.custom";
    public static final String SETTINGS_SEARCH_ENGINE_CUSTOM_ADD = "com.solarized.firedown.preferences.search.engine.custom.add";
    public static final String SETTINGS_SEARCH_ENGINE_CUSTOM_NAME = "com.solarized.firedown.preferences.search.engine.custom.name";
    public static final String SETTINGS_SEARCH_ENGINE_CUSTOM_URL = "com.solarized.firedown.preferences.search.engine.custom.url";
    public static final String SETTINGS_SEARCH_ENGINE_CUSTOM_SUGGESTION = "com.solarized.firedown.preferences.search.engine.custom.suggestion";

    /**
     * Sentinel stored in {@link #SETTINGS_SEARCH_ENGINE} while the custom
     * engine is selected. A sentinel — not the custom name — so renaming the
     * engine keeps the selection, and a deleted/renamed custom engine can
     * never shadow (or be shadowed by) a built-in. SearchFragment's name
     * validation rejects this value (and the built-in names) as a custom
     * engine name.
     */
    public static final String CUSTOM_SEARCH_ENGINE = "@custom";

    public static final String SETTINGS_ABOUT = "com.solarized.firedown.preferences.about";

    public static final String SETTINGS_LICENSE = "com.solarized.firedown.preferences.license";

    public static final String SETTINGS_SUPPORT = "com.solarized.firedown.preferences.support";

    public static final String SETTINGS_TABS_ARCHIVE = "com.solarized.firedown.preferences.tabs.archive";

    public static final String SETTINGS_TABS_ARCHIVE_LAST_RUN = "com.solarized.firedown.preferences.tabs.archive.last.run";
    public static final String SETTINGS_TABS_ARCHIVE_INTERVAL = "com.solarized.firedown.preferences.tabs.archive.interval";

    /** Last time the web-history retention purge ran, used to throttle it to once/day. */
    public static final String SETTINGS_HISTORY_PURGE_LAST_RUN = "com.solarized.firedown.preferences.history.purge.last.run";

    /**
     * Snapshot of the archived-tab count at the moment the user last
     * dismissed the archive banner. The banner re-appears when the live
     * archived count exceeds this snapshot; tapping dismiss writes the
     * current count back so the banner stays gone until *more* tabs land
     * in the archive. Matches the count-driven inactive-tabs UX in
     * Fennec / Chrome / Edge.
     */
    public static final String SETTINGS_TABS_ARCHIVE_BANNER_DISMISSED_AT = "com.solarized.firedown.preferences.tabs.archive.banner.dismissed.at";

    /**
     * Cached archived-tab count windowed by {@link #SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL}.
     * Written by the banner observer in TabsFragment whenever the
     * archived-count LiveData fires; read synchronously on the next
     * fragment open so we can decide whether to show the banner row
     * without waiting on the Room count query. Removes the ~250 ms
     * spinner gap between "open tabs page" and "tabs visible". Matches
     * how Chrome / Fenix avoid spinners on tab-switcher open — both
     * keep their tab + chrome state in an already-warm in-memory
     * store; for our archive count which lives in Room a small
     * SharedPreferences cache is the equivalent.
     *
     * <p>Default {@code -1} means "no cache, fall back to async wait".
     * The interval is stored alongside so a settings change
     * (day / week / month) invalidates the cache.</p>
     */
    public static final String SETTINGS_TABS_ARCHIVE_BANNER_LAST_COUNT = "com.solarized.firedown.preferences.tabs.archive.banner.last.count";
    public static final String SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL = "com.solarized.firedown.preferences.tabs.archive.banner.last.interval";

    public static final String SETTINGS_BLOCK_COOKIE_NOTICES = "com.solarized.firedown.preferences.ublock.block.cookie.notices";
    /** Default ON: consent banners are the web's biggest annoyance and hiding
     *  one never GRANTS consent (no stored answer = GDPR's no). Flipping the
     *  default needed no new key — no semantic inversion (true still means
     *  "block") and defaults are never persisted, same as the app-redirects
     *  flip. The toggle stays for the rare consent-gated site. */
    public static final boolean DEFAULT_BLOCK_COOKIE_NOTICES = true;

    public static final String DEFAULT_SEARCH_ENGINE = "DuckDuckGo";

    public static final String DEFAULT_SEARCH_AUTOCOMPLETE = "https://duckduckgo.com/ac/?q=%s&type=list";

    public static final String DEFAULT_SEARCH_FORMAT = "https://duckduckgo.com/?q=%s&ia=web";

    public static final int DEFAULT_DOWNLOADS = 0;

    public static final long FIVE_MINUTES_INTERVAL = 300_000L;
    public static final long FIFTEEN_MINUTES_INTERVAL = 900_000L;
    public static final long ONE_HOUR_INTERVAL = 3_600_000L;
    public static final long ONE_DAY_INTERVAL = 86_400_000L;
    public static final long ONE_WEEK_INTERVAL = 604_800_000L;
    public static final long THIRTY_DAYS_INTERVAL = 2_592_000_000L;
    public static final long NEVER_INTERVAL     = -1L;

    /** Web-history retention window: entries older than this are purged.
     *  NEVER_INTERVAL disables purging — history is kept indefinitely (like
     *  Firefox, which expires history by storage size, not a fixed age). The
     *  user can still clear it manually via the Delete-browsing dialog.
     *  WebHistoryDataRepository.purgeDatabase guards this value (a non-positive
     *  window must skip the purge, not delete everything). */
    public static final long HISTORY_RETENTION_INTERVAL = NEVER_INTERVAL;

    // Archive hygiene bounds, enforced on every auto-archive sweep
    // (GeckoStateDataRepository.archiveInactiveTabsLocked) so the archive
    // can't grow without limit — each archived row carries a full serialized
    // sessionState blob, so an "archive after 1 week" user would otherwise
    // accumulate them forever. No setting: these are sane fixed bounds. The
    // age horizon sits ABOVE the longest auto-archive interval (30 days) so a
    // tab the archive itself just moved is never immediately re-purged.
    /** Never keep more than this many archived tabs (newest by archive time). */
    public static final int  TABS_ARCHIVE_MAX_COUNT       = 200;
    /** Drop archived tabs whose archive time is older than this. */
    public static final long TABS_ARCHIVE_MAX_AGE_INTERVAL = 90L * ONE_DAY_INTERVAL;

    public static final int LIST_LIMIT = 25;



    public static boolean getJavascriptEnabled(SharedPreferences sharedPreferences){
        Log.d(TAG, "getJavascriptEnabled : " + !sharedPreferences.getBoolean(SETTINGS_BLOCK_JAVASCRIPT, false));
        return !sharedPreferences.getBoolean(SETTINGS_BLOCK_JAVASCRIPT, false);
    }

    public static boolean getSaveToGallery(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_GALLERY, false);
    }

    /** Whether DNS-over-HTTPS is enabled (the master toggle). */
    public static boolean getDohEnabled(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_DOH_SWITCH, false);
    }

    /**
     * The configured DoH endpoint URL: the persisted {@link #SETTINGS_DOH}
     * value is itself the URL for presets, or the user-entered
     * {@link #SETTINGS_DOH_CUSTOM} when the {@link #SETTINGS_DOH_CUSTOM_VALUE}
     * sentinel is selected. Single source of truth for every DoH consumer
     * (GeckoView TRR at boot/change, and the OkHttp resolver).
     */
    public static String getDohUri(SharedPreferences sharedPreferences){
        String value = sharedPreferences.getString(SETTINGS_DOH, DEFAULT_SETTINGS_DOH);
        if (SETTINGS_DOH_CUSTOM_VALUE.equals(value)) {
            return sharedPreferences.getString(SETTINGS_DOH_CUSTOM, "");
        }
        return value;
    }

    public static int getAntiTrackingCategories(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.AntiTracking.STRICT;
        }else if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_DEFAULT, true)
                || sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_CUSTOM, false)){
            return ContentBlocking.AntiTracking.DEFAULT;
        }else{
            return ContentBlocking.AntiTracking.NONE;
        }
    }

    public static int getEnhancedTrackingProtectionLevel(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.EtpLevel.STRICT;
        }else if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_DEFAULT, true)
                || sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_CUSTOM, false)){
            return ContentBlocking.EtpLevel.DEFAULT;
        }else{
            return ContentBlocking.EtpLevel.NONE;
        }
    }

    public static int getEnhancedTrackingProtectionCategories(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.EtpCategory.STRICT;
        }else{
            return ContentBlocking.EtpCategory.STANDARD;
        }
    }

    public static boolean getQueryParameterStrippingEnabled(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_CUSTOM, false);
    }

    public static String[] getQueryParameterStripList(SharedPreferences sharedPreferences){
        String raw = sharedPreferences.getString(SETTINGS_ANTI_TRACKING_STRIP_LIST, DEFAULT_QUERY_STRIP_LIST);
        if(raw == null) return new String[0];
        String trimmed = raw.trim();
        if(trimmed.isEmpty()) return new String[0];
        return trimmed.split("\\s+");
    }

    /**
     * DRM defaults to off. Firedown can't download DRM-protected content,
     * so the request_media_key_system_access prompt is dead-end noise for
     * the few sites that still gate playback on it. Users who want DRM
     * playback flip the toggle in settings.
     */
    public static boolean getDRMEnabled(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_ENABLE_DRM, false);
    }


    public static int getCookieBehavior(SharedPreferences sharedPreferences){

        String cookieValue = sharedPreferences.getString(SETTINGS_COOKIES, DEFAULT_SETTINGS_COOKIES);

        return switch (cookieValue) {
            case "3" -> ContentBlocking.CookieBehavior.ACCEPT_VISITED;
            case "1" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY;
            case "4" -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS;
            case "5" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS;
            case "2" -> ContentBlocking.CookieBehavior.ACCEPT_NONE;
            default -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS;
        };
    }



}
