// Page-world state media handlers (generic page-state-bridge backend) plus the
// Mega.nz folder/file handlers — split verbatim out of the former
// parser-background.js. The old typeof-guarded globalThis reads of
// matchInParserBlocklist / __getAmbientHeaders are now real module imports —
// the guards existed only because the classic script ran before the deferred
// modules published them, which module evaluation order makes impossible.
import { log, sendNative, sendVariants, enumerateMasterNative, decodeHtmlEntities, registerMessageHandler } from './common.js';
import { matchInParserBlocklist } from '../parser-blocklist.js';
import { getAmbientHeaders } from '../requests.js';

// Page-world state media (generic) — backs Bilibili.tv and any state-inlining
// site
// ----------------------------------------------------------------------------
// Some sites SSR-inline the playurl into a page-world JS global and fire no
// playurl XHR — bilibili.tv is the canonical case: window.__initialState (a
// devalue IIFE) carries player.playUrl.dash.{video[],audio[]}. The wire and the
// DOM both miss it; only page-world JS can read it. The generic
// page-state-bridge.js content script (ONE file, <all_urls>, reads via the Xray
// wrappedJSObject waiver — no inject/WAR/CSP, no per-site files) finds the DASH
// slice, builds video+audio variants, and posts them here.
//
// Each rep's baseUrl is ONE complete .m4s track (DASH SegmentBase, byte-range
// accessed) — not a segment list — so {url: video, audioUrl: audio} routes to
// FFmpegMergeStrategy, which muxes the two whole-track files natively
// (FFmpegOkhttp does the range fetches). No ffmpeg.wasm.
//
// Referer: anti-leech CDNs (e.g. bilibili's upos/bilivideo, which truncates
// without one) check the page origin, so we attach the page's own origin
// generically — no per-site host check. For bilibili the emitted .m4s baseUrls
// stay block-listed in regex.js so the generic catcher can't dupe them.
// ============================================================================

registerMessageHandler("page-state-media", (message, sender) => {
    const p = message.payload;
    if (!p || !Array.isArray(p.variants) || p.variants.length === 0) return;

    const tabId = sender.tab?.id ?? -1;
    const pageUrl = p.origin || sender.tab?.url || "";
    const details = {
        tabId,
        _resolvedTabId: tabId >= 0 ? tabId : undefined,
        url: pageUrl,
        requestId: `page-state-${Date.now()}`
    };

    // Attach the page's own origin as Referer so the native re-fetch
    // authenticates against anti-leech CDNs. Generic — derived from the page URL.
    let requestHeaders;
    try {
        requestHeaders = [{ name: "Referer", value: new URL(pageUrl).origin + "/" }];
    } catch (_) {
        requestHeaders = [];
    }

    log("PAGE-STATE", `received ${p.variants.length} variant(s)`, {
        title: p.title, origin: pageUrl.slice(0, 80), tabId
    });

    sendVariants(details, {
        variants: p.variants,
        origin: pageUrl,
        description: p.title,
        name: p.title,
        img: p.img,
        duration: p.durationMs > 0 ? p.durationMs : 0,
        requestHeaders
    });
});

// Progressive (single-URL) media variants read GENERICALLY from any page-world
// player's source/quality list (page-state-bridge readPlayerMedia → emitOneGroup),
// for any site that holds a playable URL in a JS global before the player fetches
// it on play (a custom player config, flashvars, a framework store, …). Some such
// players resolve their list through a same-origin JSON delegate whose body the
// generic catcher rejects (application/json), so nothing is captured until play;
// the bridge reads the list page-world (resolving any delegate) and posts the real
// progressive URLs here. Routed through sendVariants as progressive files
// (skipProbe is auto-set from any page duration). No special requestHeaders — and
// none are needed for the common case: these media URLs are query-signed and
// self-authorizing (verified in practice: the real browser fetch carries no
// Referer/Origin/Cookie). The played URL dedups by URL against this; for known
// CDN families a parser-blocklist.js block also covers a manually-selected
// other-quality URL, otherwise we rely on the URL dedup.
registerMessageHandler("page-state-progressive", (message, sender) => {
    const p = message.payload;
    if (!p || !Array.isArray(p.variants) || p.variants.length === 0) return;

    // Skip if a dedicated parser owns this host (same rationale as the HLS path
    // above — avoid the bridge duplicating a parser-owned capture). Check the
    // primary variant URL against the shared blocklist.
    const primaryUrl = p.variants[0] && p.variants[0].url;
    if (typeof primaryUrl === "string" && matchInParserBlocklist(primaryUrl)) {
        log("PAGE-STATE", `skip progressive — parser-owned host`, { url: primaryUrl.slice(0, 80) });
        return;
    }

    const tabId = sender.tab?.id ?? -1;
    const pageUrl = p.origin || sender.tab?.url || "";
    const details = {
        tabId,
        _resolvedTabId: tabId >= 0 ? tabId : undefined,
        url: pageUrl,
        requestId: `page-state-prog-${Date.now()}`
    };

    log("PAGE-STATE", `received ${p.variants.length} progressive variant(s)`, {
        title: p.title, origin: pageUrl.slice(0, 80), tabId
    });

    // Replicate the browser's <video>-element request shape. These URLs are
    // query-signed/self-authorizing (no Referer/Origin/Cookie — verified on a
    // self-authorizing CDN that serves a header-LESS GET fine), but some
    // progressive CDNs gate on the MEDIA-REQUEST headers a real <video> fetch
    // always carries: krakencloud's /play/video/<token> (series.ly) 404s a bare
    // GET (and a UA-only ffmpeg probe), yet the SAME url + token plays in-browser
    // as a 206 — the difference is the `Accept: video/*` + `Sec-Fetch-Dest: video`
    // + UA/Accept-Language set. Send those (NOT Referer/Origin, which the working
    // play omits and such CDNs don't need). Benign for self-authorizing CDNs (a
    // real browser sends them too); a
    // CDN that also fingerprints TLS (JA3/JA4) is still unreachable to OkHttp.
    const requestHeaders = [
        { name: "Accept", value: "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5" },
        { name: "Sec-Fetch-Dest", value: "video" },
        { name: "Sec-Fetch-Mode", value: "no-cors" },
        { name: "Sec-Fetch-Site", value: "cross-site" }
    ];
    if (typeof p.lang === "string" && p.lang) requestHeaders.push({ name: "Accept-Language", value: p.lang });
    if (typeof p.ua === "string" && p.ua) requestHeaders.push({ name: "User-Agent", value: p.ua });

    sendVariants(details, {
        variants: p.variants,
        origin: pageUrl,
        description: p.title,
        name: p.title,
        img: p.img,
        duration: p.durationMs > 0 ? p.durationMs : 0,
        requestHeaders,
        // Per-CLIP dedup: a single page can hold MANY distinct clips (multiple
        // players in one document), all with this same page origin — keying dedup
        // on the primary media URL lets each clip through instead of collapsing
        // them to the first. Cross-pass re-emit of the same clip is still gated by
        // the bridge's URL sentKeys + the repository's url-hash dedup.
        dedupKey: primaryUrl
    });
});

// HLS master read from a page-world JS player (page-state-bridge findPlayerHls):
// a site whose player fetches the (often obfuscated) master only on PLAY
// (preload:none) is invisible to the wire until the user clicks, but the player
// holds the de-obfuscated url at setup — the bridge reads it and posts it here.
// Routed through the normal HLS-master path (Java OkHttp-enumerates qualities,
// no probe), with the embed iframe's origin as Referer so the master fetch
// authenticates. enumerateMasterNative owns origin dedup; when the user does
// press play the wire sees the SAME signed master URL and the repository dedups
// it by URL, while its raw .ts segments are dropped natively (format==mpegts).
registerMessageHandler("page-state-hls", (message, sender) => {
    // Fire-and-forget async (we owe the bridge no response); the shared router
    // never returns a handler's value, so this can't become a responder.
    handlePageStateHls(message, sender);
});

async function handlePageStateHls(message, sender) {
    const p = message.payload;
    if (!p || typeof p.url !== "string") return;

    // The page-state bridge's generic readers can pick up media on a site that
    // already has a DEDICATED parser (e.g. Dailymotion's player exposes its HLS
    // master, which findPlayerMedia reads — but processDailymotionData already
    // captures it via the geo API). Those two emits carry different origins and
    // rotating signed tokens, so neither the URL nor the origin dedup collapses
    // them → a duplicate entry. Now that the blocklist lives in this same
    // extension, consult it: if a dedicated parser owns this host, skip the
    // bridge emit and let the parser own the capture. This is the SAME oracle the
    // generic catcher uses, applied here ONLY to the bridge's generic readers —
    // the host-keyed branches (Bilibili page-state-media, Mega) go through other
    // handlers and are unaffected; genuine generic-only hosts (e.g. series.ly)
    // aren't in the list and so are not skipped.
    if (matchInParserBlocklist(p.url)) {
        log("PAGE-STATE", `skip HLS master — parser-owned host`, { url: p.url.slice(0, 80) });
        return;
    }

    const tabId = sender.tab?.id ?? -1;
    const pageUrl = p.origin || sender.tab?.url || "";
    const details = {
        tabId,
        _resolvedTabId: tabId >= 0 ? tabId : undefined,
        url: pageUrl,
        requestId: `page-state-hls-${Date.now()}`
    };

    // Prefer the REAL ambient headers (the exact Accept-Language / User-Agent
    // Gecko sends) over the bridge's reconstruction. The bridge can only rebuild
    // Accept-Language from navigator.languages, and a format slip there once cost
    // a 403 (missing ";q=0.9"). The catcher (now in this same extension) harvests
    // the real strings off every <all_urls> request and exports them from
    // requests.js (getAmbientHeaders); read them directly (graceful fallback to the bridge's
    // reconstructed p.lang / p.ua if not yet harvested). Both are browser-global,
    // so any request's values are correct for this fetch.
    let realAcceptLanguage = null, realUserAgent = null;
    try {
        const ambient = getAmbientHeaders();
        if (ambient && typeof ambient === "object") {
            if (typeof ambient.acceptLanguage === "string" && ambient.acceptLanguage) realAcceptLanguage = ambient.acceptLanguage;
            if (typeof ambient.userAgent === "string" && ambient.userAgent) realUserAgent = ambient.userAgent;
        }
    } catch (_) { /* ambient headers not yet harvested — use reconstruction */ }

    const acceptLanguage = realAcceptLanguage || p.lang;
    const userAgent = realUserAgent || p.ua;

    // Replicate the player's EXACT master request, because a strong CDN anti-bot
    // rejects ANY deviation from a real browser request (proven on-device: the
    // ONLY difference between a 403 and a 200 was a missing ";q=0.9" on
    // Accept-Language). Every header must byte-match what hls.js's fetch sends:
    // Origin (explicit — OriginInterceptor only derives it same-site), the
    // full-path Referer (the embed iframe URL), the Sec-Fetch-* trio
    // (Sec-Fetch-Site computed, not hardcoded), and Accept-Language + User-Agent
    // (REAL harvested values, else the bridge's navigator-read ones). ffmpeg
    // propagates all of these to the playlist/segment/key sub-requests on
    // download.
    //
    // Ceiling: this matches header VALUES; the strongest systems also fingerprint
    // TLS (JA3/JA4) and header order, which the native OkHttp client can't mimic.
    let requestHeaders;
    try {
        const pageUrlObj = new URL(pageUrl);
        const playerOrigin = pageUrlObj.origin; // scheme://host
        // Sec-Fetch-Site exactly as the browser derives it for this fetch:
        // same-origin / same-site (same registrable domain) / cross-site. The
        // registrable-domain test is a last-two-labels heuristic (no PSL), so a
        // multi-part eTLD like .co.uk would mis-read same-site as cross-site —
        // acceptable here because these stream CDNs live on a different
        // registrable domain than the embed host anyway, so the common answer is
        // cross-site and the heuristic returns it correctly.
        let secSite = "cross-site";
        try {
            const mediaUrl = new URL(p.url);
            const regDomain = (h) => h.split(".").slice(-2).join(".");
            if (mediaUrl.origin === playerOrigin) secSite = "same-origin";
            else if (regDomain(mediaUrl.hostname) === regDomain(pageUrlObj.hostname)) secSite = "same-site";
        } catch (_) {}
        requestHeaders = [
            { name: "Origin", value: playerOrigin },
            { name: "Referer", value: pageUrl },        // full embed iframe URL
            { name: "Sec-Fetch-Dest", value: "empty" },
            { name: "Sec-Fetch-Mode", value: "cors" },
            { name: "Sec-Fetch-Site", value: secSite }
        ];
        if (acceptLanguage) requestHeaders.push({ name: "Accept-Language", value: acceptLanguage });
        if (userAgent) requestHeaders.push({ name: "User-Agent", value: userAgent });
    } catch (_) {
        requestHeaders = [];
    }

    log("PAGE-STATE", `received HLS master`, {
        title: p.title, url: p.url.slice(0, 80), origin: pageUrl.slice(0, 80), tabId,
        ambient: !!realAcceptLanguage
    });

    enumerateMasterNative(details, {
        url: p.url,
        origin: pageUrl,
        name: p.title,
        description: p.title,
        img: p.img,
        requestHeaders
    });
}

// Mega.nz folder link (page-state-bridge extractMega). The folder share key is
// in the URL fragment — invisible to the wire — so the bridge reads it page-world
// and hands us the folder handle + master key. We forward both to native, which
// enumerates the share tree (anonymous cs `f` call), decrypts each node key with
// the master key, and emits one entity per media file. The file bytes are
// AES-CTR ciphertext, so there's nothing to capture off the wire — the native
// MegaStrategy resolves the temp URL and decrypts on download. No origin dedup
// here: the native side dedups per file by its synthetic URL's uid, and re-enumeration is idempotent.
registerMessageHandler("mega-folder", (message, sender) => {
    const p = message.payload;
    if (!p || typeof p.folderHandle !== "string" || typeof p.masterKey !== "string") return;

    const tabId = sender.tab?.id ?? -1;
    const pageUrl = p.origin || sender.tab?.url || "";

    log("MEGA", `folder ${p.folderHandle}`, { origin: pageUrl.slice(0, 80), tabId });

    const message2 = {
        type: "mega-folder",
        url: pageUrl,
        origin: pageUrl,
        folderHandle: p.folderHandle,
        masterKey: p.masterKey,
        tabId,
        requestId: `mega-folder-${Date.now()}`
    };
    if (p.title) message2.name = decodeHtmlEntities(p.title);
    if (p.img) message2.img = p.img;

    sendNative(message2);
});

// Mega.nz single file / embed link (page-state-bridge extractMega). Same story
// as the folder case, but the 256-bit key in the fragment IS the cleartext file
// key (no master-key decryption). The native side fetches the file attributes
// (cs `g` with the public `p` handle, no g:1) for the real name + size, then
// MegaStrategy mints the temp download URL and AES-CTR-decrypts the stream. This
// is also the embedded-video path: the bridge runs in the cross-origin mega.nz
// /embed iframe (all_frames), so a Mega video embedded on a third-party page is
// captured without ever leaving that page.
registerMessageHandler("mega-file", (message, sender) => {
    const p = message.payload;
    if (!p || typeof p.fileHandle !== "string" || typeof p.fileKey !== "string") return;

    const tabId = sender.tab?.id ?? -1;
    const pageUrl = p.origin || sender.tab?.url || "";

    log("MEGA", `file ${p.fileHandle}`, { origin: pageUrl.slice(0, 80), tabId });

    const message2 = {
        type: "mega-file",
        url: pageUrl,
        origin: pageUrl,
        fileHandle: p.fileHandle,
        fileKey: p.fileKey,
        tabId,
        requestId: `mega-file-${Date.now()}`
    };
    if (p.title) message2.name = decodeHtmlEntities(p.title);
    if (p.img) message2.img = p.img;

    sendNative(message2);
});

