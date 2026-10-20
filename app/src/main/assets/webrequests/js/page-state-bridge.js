// page-state-bridge.js — ONE generic page-world → media bridge for ALL sites.
//
// Some sites SSR-inline the playable media (DASH video+audio reps) into a
// page-world JS global — window.__initialState (bilibili.tv, a devalue blob),
// __NEXT_DATA__, __NUXT__, a Redux store, … — and fire NO playurl XHR. So the
// wire (webRequest) never sees it and the DOM has no media element either; only
// page-world JS can read it. The old approach was a per-site content-script +
// page-world WAR inject pair (bilibili-tv-content.js + bilibili-tv-inject.js).
// This replaces that with a SINGLE catch-all content script, matched on
// <all_urls> (no per-site files, no per-site host permissions):
//
//   - It reads the page's real globals via Firefox's Xray **wrappedJSObject**
//     waiver — the same mechanism youtube/content.js and webrequests/
//     content-script.js already use in this GeckoView — so there is no <script>
//     inject, no web-accessible resource, and no page-CSP problem.
//   - It runs a bounded, generic structural search for a DASH {video[],audio[]}
//     slice, copies it to plain data (Xray-safe), builds video+audio variants,
//     and hands them to the background, which emits them via sendVariants →
//     native FFmpegMergeStrategy (whole-track .m4s mux, no ffmpeg.wasm).
//   - It ALSO reads a page-world JS player's RESOLVED source (JWPlayer
//     getPlaylist().file) for sites whose player fetches a (often obfuscated)
//     HLS master only on PLAY (preload:none) — the wire can't see it pre-play,
//     but the player must hold the de-obfuscated url to play, so we read it and
//     emit an hls-master. This is why it runs in subframes (all_frames): the
//     player is usually an embedded cross-origin iframe.
//
// Per-site logic that needs page-world DATA (e.g. bilibili.tv's ogv episode
// title/cover) is a host-keyed branch in `resolveMeta` HERE — this is the only
// place that holds the page-world `root`, which the background can't read.
// Per-site REQUEST/emit specifics stay in background.js. Either way: never a new
// injected file. Cheap on the ~all sites that have no such state: it probes a
// short list of known state globals by name and no-ops when none hold media.
(() => {
    'use strict';

    let DEBUG = false;
    const log = (...args) => { if (DEBUG) console.log('[PAGE-STATE]', ...args); };

    // Per-node key bound for the state walks. Object.keys() materialises EVERY
    // index of an array-like as a string, so one node could cost millions of
    // allocations regardless of the node budget: a wasm module's HEAPU8 /
    // HEAP32 (tens of MB, exposed as plain window members by non-modularised
    // Emscripten output) froze the content process when readPlayerMedia
    // scanned the globals. Typed arrays / buffers hold no media URL and are
    // skipped outright (byteLength is the tell — it crosses the Xray waiver
    // as a primitive); any other object is read through at most
    // MAX_KEYS_PER_NODE keys. Returns null for "don't walk this node".
    const MAX_KEYS_PER_NODE = 2000;
    function boundedKeys(o) {
        try {
            if (typeof o.byteLength === "number") return null;
            if (typeof o.length === "number" && o.length > MAX_KEYS_PER_NODE) return null;
        } catch (_) { return null; }
        let keys;
        try { keys = Object.keys(o); } catch (_) { return null; }
        if (keys.length > MAX_KEYS_PER_NODE) keys.length = MAX_KEYS_PER_NODE;
        return keys;
    }
    browser.runtime.sendNativeMessage("parser", { kind: "get-debug-flag" })
        .then(r => { DEBUG = r === true; }, () => {});

    // Globals sites inline their SSR/SPA page model into. Probed by name (cheap)
    // rather than walking all of window. Add names here, never per-site files.
    const STATE_GLOBALS = [
        "__initialState", "__INITIAL_STATE__", "__NUXT__", "__NEXT_DATA__",
        "__APOLLO_STATE__", "__PRELOADED_STATE__", "__REDUX_STATE__",
        "__data", "__STATE__"
    ];

    // ---- DASH shape detection (no array-callback methods — those misbehave on
    // Xray-waived page arrays, so everything here uses index loops / direct
    // reads, all wrapped defensively). ------------------------------------------
    function looksLikeRep(o) {
        return o && typeof o === "object"
            && (typeof o.baseUrl === "string" || typeof o.base_url === "string")
            && typeof o.bandwidth === "number";
    }

    function isDashShape(o) {
        if (!o || typeof o !== "object") return false;
        const v = o.video, a = o.audio;
        if (!v || !a) return false;
        let vlen, alen;
        try { vlen = v.length; alen = a.length; } catch (_) { return false; }
        if (typeof vlen !== "number" || typeof alen !== "number" || vlen === 0 || alen === 0) {
            return false;
        }
        let first;
        try { first = v[0]; } catch (_) { return false; }
        return looksLikeRep(first);
    }

    // Bounded structural search for a DASH object anywhere in a state tree.
    // depth + node caps so a huge / cyclic page model can't hang the page.
    function findDash(root) {
        const seen = new Set();
        let budget = 20000;
        let found = null;
        (function walk(o, depth) {
            if (found || !o || typeof o !== "object" || depth > 8 || budget-- <= 0) return;
            if (seen.has(o)) return;
            seen.add(o);
            if (isDashShape(o)) { found = o; return; }
            if (Array.isArray(o)) {
                let n;
                try { n = o.length; } catch (_) { return; }
                if (typeof n !== "number") return;
                for (let i = 0; i < n && i < MAX_KEYS_PER_NODE && !found; i++) {
                    let v;
                    try { v = o[i]; } catch (_) { continue; }
                    walk(v, depth + 1);
                }
                return;
            }
            const keys = boundedKeys(o);
            if (!keys) return;
            for (let i = 0; i < keys.length && !found; i++) {
                let v;
                try { v = o[keys[i]]; } catch (_) { continue; }
                walk(v, depth + 1);
            }
        })(root, 0);
        return found;
    }

    // ---- Xray-safe copy of the found DASH slice into plain data --------------
    function repToPlain(r) {
        if (!r || typeof r !== "object") return null;
        const baseUrl = (typeof r.baseUrl === "string") ? r.baseUrl
            : (typeof r.base_url === "string" ? r.base_url : null);
        if (!baseUrl) return null;
        let backupUrl;
        try {
            const b = r.backupUrl || r.backup_url;
            if (b && typeof b === "object" && typeof b.length === "number" && b.length) {
                if (typeof b[0] === "string") backupUrl = b[0];
            }
        } catch (_) {}
        return {
            baseUrl,
            backupUrl,
            bandwidth: typeof r.bandwidth === "number" ? r.bandwidth : 0,
            width: typeof r.width === "number" ? r.width : 0,
            height: typeof r.height === "number" ? r.height : 0,
            codecs: typeof r.codecs === "string" ? r.codecs : undefined
        };
    }

    function cloneRepArray(arr) {
        const out = [];
        let n;
        try { n = arr.length; } catch (_) { return out; }
        if (typeof n !== "number") return out;
        for (let i = 0; i < n && i < 200; i++) {
            let r;
            try { r = arr[i]; } catch (_) { continue; }
            const p = repToPlain(r);
            if (p) out.push(p);
        }
        return out;
    }

    function cloneDash(wDash) {
        const video = cloneRepArray(wDash.video);
        const audio = cloneRepArray(wDash.audio);
        let duration = 0;
        try { const d = Number(wDash.duration); if (d > 0) duration = d; } catch (_) {}
        return { video, audio, duration };
    }

    // ---- Variant build (now on clean content-script data) --------------------
    function buildVariants(dash) {
        if (!dash.video.length || !dash.audio.length) return null;
        const bestAudio = dash.audio.slice()
            .sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0))[0];
        if (!bestAudio.baseUrl) return null;
        const variants = dash.video.slice()
            .sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0))
            .map(v => ({
                url: v.baseUrl,
                audioUrl: bestAudio.baseUrl,
                width: v.width || 0,
                height: v.height || 0,
                videoCodec: v.codecs || undefined,
                audioCodec: bestAudio.codecs || undefined,
                videoBackupUrl: v.backupUrl || undefined,
                audioBackupUrl: bestAudio.backupUrl || undefined
            }));
        if (!variants.length) return null;
        return { variants, durationMs: dash.duration > 0 ? Math.round(dash.duration * 1000) : 0 };
    }

    // ---- Generic page metadata from the DOM (no per-site logic) --------------
    function ogMeta(prop) {
        const el = document.querySelector(`meta[property="${prop}"], meta[name="${prop}"]`);
        return el && el.getAttribute("content") ? el.getAttribute("content").trim() : "";
    }
    // Is `t` merely the SITE's name rather than this page's title? SPAs write
    // the real <title>/og:title AFTER first paint, so an early pass can see just
    // "bilibili" — and because the repository is first-capture-wins with no
    // metadata merge, that placeholder would be the entry's name forever while
    // the later retry pass carrying the real title is dropped as a duplicate.
    // Matches the host, its registrable main label, and the "www."/"m." forms.
    function isSiteNameOnly(t) {
        if (typeof t !== "string") return false;
        const s = t.trim().toLowerCase();
        if (!s) return false;
        let host = (location.hostname || "").toLowerCase();
        if (host.startsWith("www.")) host = host.slice(4);
        else if (host.startsWith("m.")) host = host.slice(2);
        if (!host) return false;
        const label = host.split(".")[0];
        return s === host || s === "www." + host || (label.length >= 2 && s === label);
    }

    function pageTitle() {
        // Prefer whichever source carries a REAL title. og: normally wins, but a
        // site-name-only og: must not shadow a usable <title> (and vice versa).
        const og = ogMeta("og:title");
        const doc = (document.title || "").split(/\s[-|]\s/)[0].trim();
        if (og && !isSiteNameOnly(og)) return og;
        if (doc && !isSiteNameOnly(doc)) return doc;
        return og || doc || location.host || "video";
    }

    // Read a string/number property off an Xray-waived object, defensively.
    function readPrim(o, key) {
        try {
            const v = o[key];
            return (typeof v === "string" || typeof v === "number") ? v : null;
        } catch (_) { return null; }
    }

    // Resolve a player-fed media URL against this frame's document base. A player
    // keeps source URLs AS AUTHORED — JWPlayer (and Video.js) only absolutize a
    // remote-playlist loader, never an inline sources[].file — so getPlaylist()
    // can hand us a ROOT-RELATIVE master ("/stream/…/master.m3u8", e.g. StreamHG/
    // series.ly jw8 embeds). hls.js resolves it against document.baseURI when it
    // fetches, but we read the player object, not the wire; mediaKindOf then drops
    // it on its ^https?:// gate (returns null) so the whole group is empty and the
    // bridge captures NOTHING — leaving only the generic catcher's bare wire master
    // (no poster). Resolve it here, the same base the player/hls.js uses (honours a
    // <base> tag), so the absolute URL classifies + emits with the page-world poster.
    // Already-absolute and blob:/data:/mediasource: URLs pass through unchanged.
    function absolutize(url) {
        if (typeof url !== "string" || !url) return url;
        try { return new URL(url, document.baseURI || location.href).href; } catch (_) { return url; }
    }

    // Bilibili.tv episode model lives at root.ogv: season.title + the playing
    // episode (ogv.epId matched against ogv.sectionsList[].episodes[].episode_id)
    // give an episode-precise "Season Episode" title and a per-episode cover.
    // Index-loop / primitive reads only (Xray-safe). Returns null if absent.
    function resolveBilibiliMeta(root) {
        let ogv;
        try { ogv = root.ogv; } catch (_) { ogv = null; }
        if (!ogv || typeof ogv !== "object") return null;

        let season;
        try { season = ogv.season || {}; } catch (_) { season = {}; }
        const seasonTitle = readPrim(season, "title");

        let epId = readPrim(ogv, "epId");
        epId = epId != null ? String(epId) : null;

        let epPart = null, cover = null;
        let sections;
        try { sections = ogv.sectionsList; } catch (_) { sections = null; }
        let slen = 0;
        try { slen = (sections && typeof sections.length === "number") ? sections.length : 0; } catch (_) { slen = 0; }
        for (let i = 0; i < slen && !epPart; i++) {
            let sec;
            try { sec = sections[i]; } catch (_) { continue; }
            let eps;
            try { eps = sec && sec.episodes; } catch (_) { eps = null; }
            let elen = 0;
            try { elen = (eps && typeof eps.length === "number") ? eps.length : 0; } catch (_) { elen = 0; }
            for (let j = 0; j < elen; j++) {
                let ep;
                try { ep = eps[j]; } catch (_) { continue; }
                const eid = readPrim(ep, "episode_id");
                if (epId && eid != null && String(eid) === epId) {
                    epPart = readPrim(ep, "title_display")
                        || readPrim(ep, "long_title_display")
                        || readPrim(ep, "short_title_display");
                    cover = readPrim(ep, "cover");
                    break;
                }
            }
        }

        let title = null;
        if (epPart) title = seasonTitle ? `${seasonTitle} ${epPart}` : epPart;
        if (!title) title = seasonTitle || null;
        const img = cover || readPrim(season, "horizontal_cover")
            || readPrim(season, "vertical_cover") || null;
        if (!title && !img) return null;
        return { title: title || undefined, img: img || undefined };
    }

    // Host-keyed metadata enrichment. Default is generic (og:/document.title +
    // og:image); a known host whose page state carries richer fields overrides
    // it. Add a host branch here (not a new content script) — this is the one
    // place that already holds the page-world `root`, which the background can't
    // read. Per-site REQUEST/emit specifics still belong in background.js.
    function resolveMeta(root) {
        let rich = null;
        try {
            if (/(?:^|\.)bilibili\.tv$/i.test(location.hostname)) {
                rich = resolveBilibiliMeta(root);
            }
        } catch (_) {}
        const title = (rich && rich.title) || pageTitle();
        return {
            title,
            img: (rich && rich.img) || ogMeta("og:image") || undefined,
            // Caller decides what to do about it — see the emit in extractAndSend.
            titleIsSiteName: isSiteNameOnly(title)
        };
    }

    // ---- Read page-world state via the Xray wrappedJSObject waiver -----------
    // Returns { root, dash } — root is the state global that held the dash slice,
    // so a host-keyed metadata resolver can read sibling fields (e.g. ogv).
    function readPageState() {
        let pw;
        try { pw = window.wrappedJSObject; } catch (_) { pw = null; }
        if (!pw) return null;
        for (const name of STATE_GLOBALS) {
            let g;
            try { g = pw[name]; } catch (_) { continue; }
            if (!g || typeof g !== "object") continue;
            // Fast known path (bilibili.tv: player.playUrl.dash) — direct reads,
            // no enumeration. Then a bounded generic search for everything else.
            try {
                const direct = g.player && g.player.playUrl && g.player.playUrl.dash;
                if (isDashShape(direct)) return { root: g, dash: direct };
            } catch (_) {}
            let dash;
            try { dash = findDash(g); } catch (_) { dash = null; }
            if (dash) return { root: g, dash };
        }
        return null;
    }

    // ---- Media the player was FED — player-agnostic (DOM / JWPlayer / Video.js) --
    // A site can hand a (often obfuscated) source to a player that fetches it only
    // on PLAY (preload:none) — so the wire never sees it until the user clicks. But
    // to play, the player must hold the DE-OBFUSCATED url, so we read it from the
    // player's resolved state: the DOM <video>/<source> it drives, or its JS API
    // (JWPlayer/Video.js) via the wrappedJSObject waiver. NOT tied to any one
    // player — see findPlayerMedia.
    // Agnostic to however the site packed the source (we read the result, not the
    // packed blob), so it's not cat-and-mouse. Index-loop / primitive reads only
    // (Xray-safe). Runs in subframes too (all_frames) because the player is
    // usually an embedded cross-origin iframe.
    // Media URL shapes, shared by the player-API reader (here) and the generic
    // page-state reader (further down): a progressive file vs an HLS master.
    const PROGRESSIVE_RE = /^https?:\/\/[^\s"']+\.(?:mp4|m4v|webm)(?:[?#]|$)/i;
    // Standalone progressive AUDIO file (podcast/article audio held in page state,
    // e.g. podverse.fm's __NEXT_DATA__.mediaUrl → a substack .mp3). Treated like a
    // progressive variant (no resolution). The incidental-sound concern that gates
    // the generic catcher's audio (urlIsStandaloneAudio) does NOT apply here: this
    // only fires for a url under a media-ish KEY in page-world STATE (NEXT_DATA/
    // Redux/config), which holds the page's CONTENT — UI dings live in code as
    // `new Audio('…')`, not in a state object under url/src/mediaUrl. Same
    // key-proximity precision as the video case.
    const AUDIO_RE = /^https?:\/\/[^\s"']+\.(?:mp3|m4a|aac|ogg|oga|opus|flac|wav|weba)(?:[?#]|$)/i;
    const HLS_MASTER_RE = /^https?:\/\/[^\s"']+\.m3u8(?:[?#]|$)/i;

    // Classify a PLAYER-FED media URL (a JWPlayer/Video.js source, or a DOM
    // <video>/<source> the player drives) as "hls" | "progressive" | "audio".
    // The url IS media by definition — the player was told to play it — so we
    // DELIBERATELY DO NOT gate on the file extension: these embeds keep serving
    // the manifest at an obfuscated / rotating extension (series.ly/vibuxer's HLS
    // master is at /master.txt, and that will keep changing). We decide the emit
    // path from the player's DECLARED type — its stable, structural signal
    // (JWPlayer "hls", a video/audio MIME, application/x-mpegurl, dash) — then a
    // .m3u8/.mpd hint, then a progressive/audio file extension, and finally
    // DEFAULT TO HLS: an unknown source on these players is an obfuscated master,
    // and the HLS path sends the full anti-bot header set AND native reroutes a
    // non-manifest body to ffmpeg, whereas the progressive path sends no headers
    // and would 403 a header-gated master. "audio" (an audio/* MIME or an
    // explicit audio file extension) rides the progressive emit but keeps its
    // kind, so the variant is marked audioOnly and Java types the entity as
    // audio, not video/mp4 — and an .mp3 never falls into the HLS default (Java's
    // master fetch can't enumerate a progressive file). Only a non-http
    // (blob:/data:/mediasource:) MSE handle returns null — that real manifest is
    // read from the player API. (The broad page-state walk does NOT use this — it
    // stays extension-strict so it can't grab a non-media URL that merely sits
    // under a media-ish key.)
    function mediaKindOf(url, type) {
        if (typeof url !== "string" || !/^https?:\/\//i.test(url)) return null;
        const ty = (typeof type === "string") ? type.toLowerCase().trim() : "";
        if (/mpegurl|m3u8|^hls$|^dash$|dash\+xml|mpd/.test(ty)) return "hls";
        if (/^audio\//.test(ty) || /^(?:aac|mp3|m4a|ogg|oga|opus|flac|wav|weba)$/.test(ty)) return "audio";
        if (/^video\//.test(ty) || /^(?:mp4|m4v|webm|mov|ogv)$/.test(ty)) return "progressive";
        if (HLS_MASTER_RE.test(url) || /\.mpd(?:[?#]|$)/i.test(url)) return "hls";
        if (PROGRESSIVE_RE.test(url)) return "progressive";
        if (AUDIO_RE.test(url)) return "audio";
        return "hls";
    }


    // Pull the RESOLVED source URL(s) the player was fed out of a JWPlayer playlist
    // item — BOTH an HLS master (.m3u8) and progressive (.mp4/.m4v/.webm) sources,
    // each with its label/height. This is "read the final url passed to the
    // player": getPlaylist() returns the player's RESOLVED sources, so a packed /
    // eval-obfuscated source is read AFTER the player de-obfuscated it (the result,
    // not the packer). Returns { variants:[{url,height}], hls:[url], title,
    // durationSec } or null.
    function readPlayerItem(item) {
        if (!item || typeof item !== "object") return null;
        const variants = [];
        const hls = [];
        const seen = new Set();
        const take = (url, label, type) => {
            url = absolutize(url);
            if (typeof url !== "string" || seen.has(url)) return;
            seen.add(url);
            const kind = mediaKindOf(url, type);
            if (kind === "hls") hls.push(url);
            // Audio has no resolution (height 0); the mark rides to Java as the
            // variant's audioOnly so the entity types/downloads as audio.
            else if (kind === "audio") variants.push({ url, height: 0, audio: true });
            else if (kind === "progressive") variants.push({ url, height: heightFrom(label, url) });
        };
        // The item's own resolved `file`, then each entry of `sources` (qualities).
        take(readPrim(item, "file"), readPrim(item, "label") || readPrim(item, "height"), readPrim(item, "type"));
        // jw8 normalizes a playlist item into TWO lists: `allSources` = every
        // playable source the item was given, `sources` = only those the
        // provider it picked handles — so on a JW Platform embed (HAR
        // lasprovincias.es 26-09-16: playback.json lists the HLS master + two
        // mp4 renditions + an m4a) `sources` holds the master alone and the mp4
        // renditions exist only under `allSources`. Read both (byUrl below
        // dedups): the mp4s are this clip's renditions, and emitOneGroup needs
        // the whole set to claim them so the catcher doesn't emit them as
        // separate videos when the embed's og:video/twitter:player:stream name
        // them.
        const readSourceList = (list) => {
            let n = 0;
            try { n = (list && typeof list.length === "number") ? list.length : 0; } catch (_) { n = 0; }
            for (let i = 0; i < n; i++) {
                let s;
                try { s = list[i]; } catch (_) { continue; }
                take(readPrim(s, "file"), readPrim(s, "label") || readPrim(s, "height") || readPrim(s, "quality"), readPrim(s, "type"));
            }
        };
        let sources, allSources;
        try { sources = item.sources; } catch (_) { sources = null; }
        try { allSources = item.allSources; } catch (_) { allSources = null; }
        readSourceList(sources);
        readSourceList(allSources);
        if (!variants.length && !hls.length) return null;
        const t = readPrim(item, "title");
        const d = Number(readPrim(item, "duration"));
        // Poster from the player config: JWPlayer item carries `image`.
        const image = readPrim(item, "image") || readPrim(item, "poster");
        // JW Platform's id for the clip (stable across loads, unlike a signed
        // master's path): its identity on a page that holds several.
        const mediaid = readPrim(item, "mediaid");
        return {
            variants, hls, delegates: [],
            img: (typeof image === "string" && /^https?:\/\//i.test(image)) ? image : undefined,
            title: (typeof t === "string" && t.trim()) ? t.trim() : null,
            durationSec: (isFinite(d) && d > 0) ? d : 0,
            clipId: (typeof mediaid === "string" && /^[\w-]{4,64}$/.test(mediaid)) ? mediaid : null
        };
    }

    // Read the media a player was FED — the most precise "final url passed to the
    // player", de-obfuscation-proof (we read the RESOLVED value, never the packed
    // blob). PLAYER-AGNOSTIC: three readers, each independent, results merged —
    //   1. readDomMedia  — the DOM <video>/<audio> + <source> elements. The
    //      backbone: any player driving a real HTML5 element (Plyr, Video.js with a
    //      native source, plain HTML5, series.ly/krakenfiles) exposes the resolved
    //      source here, incl. an EXTENSIONLESS one classified by its <source> type.
    //      A blob:/MSE src is skipped (HLS/DASH over MSE) — that manifest is read
    //      from a player API below.
    //   2. readJwPlayer  — jwplayer().getPlaylist() (jw8: vibuxer/luluvdo/…).
    //   3. readVideoJs   — videojs.getAllPlayers()[].currentSources().
    // (2)/(3) are the ONLY place the real URL lives when the element holds a blob
    // (HLS over hls.js/dash.js). Add another player API as a new reader here, never
    // a per-site file. Returns an array of media groups (see readPlayerItem) or null.
    function findPlayerMedia() {
        const groups = [];
        // The DOM read keeps every element only in a frame that actually looks
        // like a player/embed (a known player global or a player container) —
        // the bridge runs in EVERY frame on EVERY site, so an ungated <video>
        // scan would capture inline article videos site-wide. Elsewhere it keeps
        // only an element whose sources DECLARE a quality ladder (two or more
        // heights, from size/label/data-quality or a 720p-style URL): the
        // content script reports those <source>s one by one, each a capture of
        // its own with no quality picker, and only the bridge reads them as the
        // ONE clip they are. The API readers are self-gating (the global must
        // exist), so they always run, and they run FIRST: a player API knows
        // the clip's name, poster and every source, so a DOM group holding any
        // URL an API group holds is the same clip seen through its <video>
        // tech, and is dropped (it used to be emitted first, page-titled, and
        // the API group's master became a second row of the same clip).
        //
        // On a page holding SEVERAL clips, an element outside any player
        // container is not a player's: it is a plain <audio>/<video> the
        // content script reports, naming it from its own card (a news front
        // page's podcast cards, lasprovincias.es HAR 26-10-19). The bridge
        // only knows the page's title and og:image, and its emit CLAIMS the
        // URL, which rejected the content script's well-named report: three
        // podcasts captured as "Las Provincias - Diario de noticias…" with the
        // site logo. Such an element is left to the content script; inside a
        // player container, or alone in its frame (an embed), it is read.
        const jw = readJwPlayer(); // single group | null
        if (jw) groups.push(jw);
        const vjs = readVideoJs(); // array of per-player groups | null
        if (vjs) for (let i = 0; i < vjs.length; i++) groups.push(vjs[i]);
        const covered = new Set();
        const apiRoots = [];
        for (let i = 0; i < groups.length; i++) {
            const g = groups[i];
            for (let j = 0; j < g.variants.length; j++) covered.add(g.variants[j].url);
            for (let j = 0; j < g.hls.length; j++) covered.add(g.hls[j]);
            // Looked up in this script's own document, so containment below
            // compares like with like (the API handed out a page-world node).
            let root = null;
            try { root = g.rootId ? document.getElementById(g.rootId) : null; } catch (_) { root = null; }
            if (root) apiRoots.push(root);
        }
        const playerFrame = looksLikePlayerFrame();
        const dom = readDomMedia(); // array of per-element groups | null
        if (dom) {
            for (let i = 0; i < dom.length; i++) {
                const g = dom[i];
                if (groupUrls(g).some((u) => covered.has(u))) continue;
                // The media element of a player its API already described plays
                // whatever the player hands it — the content, or a PRE-ROLL ad
                // while the API still lists the content (IMA / VAST on mobile).
                // The API group is the clip; the element's own src is not one.
                if (apiRoots.some((root) => { try { return root.contains(g.el); } catch (_) { return false; } })) continue;
                const playerOwned = playerFrame && (g.inPlayer || g.soleClip);
                if (playerOwned || isQualityLadder(g)) groups.push(g);
            }
        }
        return groups.length ? groups : null;
    }

    function groupUrls(grp) {
        const out = [];
        for (let i = 0; i < grp.variants.length; i++) out.push(grp.variants[i].url);
        for (let i = 0; i < grp.hls.length; i++) out.push(grp.hls[i]);
        return out;
    }

    // A recognised player's container (Plyr, Video.js, JW, Flowplayer, or a
    // site's own data-player wrapper).
    const PLAYER_CONTAINER_SEL = ".plyr,.video-js,.jwplayer,.jw-video,.fp-player,[data-plyr],[data-player]";

    // A media group whose video renditions name at least two different heights.
    function isQualityLadder(grp) {
        const heights = new Set();
        const vs = grp && grp.variants ? grp.variants : [];
        for (let i = 0; i < vs.length; i++) {
            if (!vs[i].audio && vs[i].height > 0) heights.add(vs[i].height);
        }
        return heights.size >= 2;
    }

    // True when this frame hosts a recognised player/embed: a known player global
    // (page-world) or a common player container. Keeps the DOM <video> scan off the
    // countless content sites that merely have an inline <video>.
    function looksLikePlayerFrame() {
        let pw;
        try { pw = window.wrappedJSObject; } catch (_) { pw = null; }
        if (pw) {
            const names = ["Plyr", "videojs", "jwplayer", "Hls", "dashjs",
                "fluidPlayer", "Clappr", "DPlayer", "Playerjs", "videojs5"];
            for (let i = 0; i < names.length; i++) {
                try { if (pw[names[i]]) return true; } catch (_) {}
            }
        }
        try {
            if (document.querySelector(PLAYER_CONTAINER_SEL)) return true;
        } catch (_) {}
        return false;
    }

    // A per-clip title from the EXPLICIT, author-provided signals on/around a
    // media element — NOT a guess from nearby headings (noisy on real pages). In
    // order: the element's own title / aria-label / data-title(-video-title), an
    // aria-labelledby target's text, then an enclosing <figure>'s <figcaption>.
    // Returns a trimmed, length-capped string or null (caller falls back to the
    // page title). Pure DOM reads — safe on an Xray-wrapped element.
    // A player's config kept as JSON in a data attribute names its clip:
    // Video.js reads its options from data-setup, Plyr from data-plyr-config,
    // and the Daily Mail's player from data-opts ({"title":"Police officer
    // shot in city centre…","src":"…mp4",…}, on every <video> of its front
    // page, whose clips all took the page's title "UK Home | Daily Mail
    // Online"). The attribute's name is no rule: any data-* attribute holding
    // a JSON object counts, and the shape decides — a string title (or
    // videoTitle, headline) at the top, or a title or name under media /
    // video / metadata / mediainfo / clip. content-script.js reads the same
    // (configTitleFor there).
    const CONFIG_TITLE_KEYS = ["title", "videoTitle", "video_title", "headline"];
    const CONFIG_NEST_KEYS = ["media", "video", "metadata", "mediainfo", "clip"];
    const CONFIG_ATTR_MAX = 100000;
    function configTitleFor(el) {
        let attrs;
        try { attrs = el && el.attributes; } catch (_) { attrs = null; }
        if (!attrs) return null;
        const str = (o, k) => {
            const v = o ? o[k] : null;
            if (typeof v !== "string") return null;
            const t = v.replace(/\s+/g, " ").trim();
            return t && !/^https?:\/\//i.test(t) ? t.slice(0, 300) : null;
        };
        for (let i = 0; i < attrs.length && i < 40; i++) {
            const a = attrs[i];
            if (!a || !/^data-/i.test(a.name)) continue;
            const v = a.value || "";
            if (v.length > CONFIG_ATTR_MAX || !/^\s*\{/.test(v)) continue;
            let o;
            try { o = JSON.parse(v); } catch (_) { continue; }
            if (!o || typeof o !== "object" || Array.isArray(o)) continue;
            for (const k of CONFIG_TITLE_KEYS) {
                const t = str(o, k);
                if (t) return t;
            }
            for (const n of CONFIG_NEST_KEYS) {
                const inner = o[n];
                if (!inner || typeof inner !== "object" || Array.isArray(inner)) continue;
                const t = str(inner, "title") || str(inner, "name");
                if (t) return t;
            }
        }
        return null;
    }

    function domTitleFor(el) {
        if (!el) return null;
        const clean = (s) => (typeof s === "string" && s.trim()) ? s.trim().slice(0, 300) : null;
        let v;
        try { v = el.getAttribute("title"); } catch (_) {}
        if (clean(v)) return clean(v);
        try { v = el.getAttribute("aria-label"); } catch (_) {}
        if (clean(v)) return clean(v);
        try { v = el.getAttribute("data-title") || el.getAttribute("data-video-title"); } catch (_) {}
        if (clean(v)) return clean(v);
        v = configTitleFor(el);
        if (v) return v;
        try {
            const id = el.getAttribute("aria-labelledby");
            if (id) {
                const ref = document.getElementById(id);
                if (ref) { const x = clean(ref.textContent); if (x) return x; }
            }
        } catch (_) {}
        try {
            const fig = el.closest ? el.closest("figure") : null;
            const cap = fig && fig.querySelector ? fig.querySelector("figcaption") : null;
            if (cap) { const x = clean(cap.textContent); if (x) return x; }
        } catch (_) {}
        return null;
    }

    // Player-agnostic DOM read: each <video>/<audio> element is its OWN clip —
    // ONE media group PER element (its src/currentSrc + <source> children + its
    // own poster). Multiple players in one document therefore become multiple
    // entities, NOT one merged entry whose primary is the first clip and whose
    // "quality variants" are actually different clips (the playbook's
    // "one entity per video, no merging"). A single element's <source> children
    // still group together as that clip's qualities. Returns an ARRAY of groups,
    // or null. (findPlayerMedia drops a group a player API already covers — the
    // <video> tech of a Video.js player — and sentKeys in emitOneGroup still
    // collapses a url seen twice across passes.)
    function readDomMedia() {
        let els;
        try { els = document.querySelectorAll("video, audio"); } catch (_) { return null; }
        const n = els ? els.length : 0;
        if (!n) return null;

        const groups = [];
        for (let i = 0; i < n; i++) {
            const el = els[i];
            // An <audio> ELEMENT declares its sources audio regardless of the
            // file extension (a tokenized/.mp4-container src on <audio> is still
            // audio — m4a) — the element is the site's own statement of kind.
            let audioEl = false;
            try { audioEl = /^audio$/i.test(el.tagName || ""); } catch (_) { audioEl = false; }
            // Collect (url -> {type,label}) for THIS element, deduped, preferring an
            // EXPLICIT type over none. A <video> with a <source type="video/mp4">
            // ALSO exposes the SAME url via currentSrc with NO type — without this
            // per-element merge that one url is classified twice (progressive from
            // the typed <source>, HLS from the typeless currentSrc default).
            const byUrl = new Map();
            const add = (url, type, label) => {
                url = absolutize(url);
                if (typeof url !== "string" || !/^https?:\/\//i.test(url)) return;
                const prev = byUrl.get(url);
                if (!prev) { byUrl.set(url, { type: type || "", label: label || "" }); return; }
                if (!prev.type && type) prev.type = type;
                if (!prev.label && label) prev.label = label;
            };
            let img;
            let poster;
            try { poster = el.getAttribute("poster"); } catch (_) {}
            poster = absolutize(poster);
            if (typeof poster === "string" && /^https?:\/\//i.test(poster)) img = poster;
            // currentSrc resolves to the playing URL (a blob for MSE — rejected by
            // mediaKindOf); the src attribute is the declared one.
            let cur, attr, etype;
            try { cur = el.currentSrc; } catch (_) {}
            try { attr = el.getAttribute("src"); } catch (_) {}
            try { etype = el.getAttribute("type"); } catch (_) {}
            add(cur, etype);
            add(attr, etype);
            let sources;
            try { sources = el.querySelectorAll("source"); } catch (_) { sources = null; }
            const sn = sources ? sources.length : 0;
            for (let j = 0; j < sn; j++) {
                const s = sources[j];
                let su, st, sl;
                try { su = s.getAttribute("src"); } catch (_) {}
                try { st = s.getAttribute("type"); } catch (_) {}
                try { sl = s.getAttribute("size") || s.getAttribute("label") || s.getAttribute("data-quality"); } catch (_) {}
                add(su, st, sl);
            }
            const variants = [];
            const hls = [];
            for (const entry of byUrl) {
                const url = entry[0];
                const info = entry[1];
                const kind = mediaKindOf(url, info.type);
                if (kind === "hls") hls.push(url);
                else if (kind === "audio" || (kind === "progressive" && audioEl)) {
                    variants.push({ url, height: 0, audio: true });
                }
                else if (kind === "progressive") variants.push({ url, height: heightFrom(info.label, url) });
            }
            if (variants.length || hls.length) {
                let inPlayer = false;
                try { inPlayer = !!(el.closest && el.closest(PLAYER_CONTAINER_SEL)); } catch (_) { inPlayer = false; }
                groups.push({ variants, hls, delegates: [], img, title: domTitleFor(el), durationSec: 0,
                    inPlayer, soleClip: n === 1, el });
            }
        }
        if (!groups.length) return null;
        log("player-probe: DOM <video>/<source> @", location.host, "groups=", groups.length);
        return groups;
    }

    // JWPlayer (jw8): jwplayer().getPlaylist() → [{file, sources:[{file,label}],
    // title}], resolved at setup() — preload:none defers only the FETCH, not setup,
    // so the url is present on load. Returns a media group or null.
    function readJwPlayer() {
        let pw;
        try { pw = window.wrappedJSObject; } catch (_) { pw = null; }
        if (!pw) return null;
        let jw;
        try { jw = pw.jwplayer; } catch (_) { jw = null; }
        if (typeof jw !== "function") return null;

        log("player-probe: jwplayer present @", location.host);
        let api;
        try { api = jw(); } catch (e) { log("player-probe: jwplayer() threw:", e && e.message); return null; }
        if (!api || typeof api !== "object") { log("player-probe: jwplayer() gave no api (player not set up yet?)"); return null; }
        let getPl;
        try { getPl = api.getPlaylist; } catch (_) { getPl = null; }
        if (typeof getPl !== "function") { log("player-probe: api has no getPlaylist()"); return null; }
        let pl;
        try { pl = api.getPlaylist(); } catch (e) { log("player-probe: getPlaylist() threw:", e && e.message); return null; }
        let n = 0;
        try { n = (pl && typeof pl.length === "number") ? pl.length : 0; } catch (_) { n = 0; }
        log("player-probe: getPlaylist length =", n);
        for (let i = 0; i < n; i++) {
            let it;
            try { it = pl[i]; } catch (_) { continue; }
            const grp = readPlayerItem(it);
            log("player-probe: jw item", i, "variants=", grp ? grp.variants.length : 0, "hls=", grp ? grp.hls.length : 0);
            if (grp) {
                const id = readPrim(api, "id");
                grp.rootId = typeof id === "string" ? id : null;
                return grp;
            }
        }
        log("player-probe: no playable source in jw playlist");
        return null;
    }

    // Whether a label is the one Video.js puts on every player root, in English
    // or in the player's language.
    function isVideoJsSelfLabel(pl, label) {
        const keys = ["Video Player", "Audio Player"];
        for (let i = 0; i < keys.length; i++) {
            if (label === keys[i]) return true;
            let local = null;
            try { local = typeof pl.localize === "function" ? pl.localize(keys[i]) : null; } catch (_) { local = null; }
            if (typeof local === "string" && label === local) return true;
        }
        return false;
    }

    // Video.js: videojs.getAllPlayers() → each player's currentSources() (or src()).
    // currentSources() holds the real .m3u8 even when the <video> shows a blob.
    // ONE group PER player (with that player's own poster()) — multiple Video.js
    // instances in one document are distinct clips, same rule as readDomMedia.
    // Returns an ARRAY of groups, or null.
    function readVideoJs() {
        let pw;
        try { pw = window.wrappedJSObject; } catch (_) { pw = null; }
        if (!pw) return null;
        let vjs;
        try { vjs = pw.videojs; } catch (_) { vjs = null; }
        if (typeof vjs !== "function") return null;

        log("player-probe: videojs present @", location.host);
        let players;
        try { players = vjs.getAllPlayers ? vjs.getAllPlayers() : null; } catch (_) { players = null; }
        let n = 0;
        try { n = (players && typeof players.length === "number") ? players.length : 0; } catch (_) { n = 0; }

        const groups = [];
        for (let i = 0; i < n; i++) {
            let pl;
            try { pl = players[i]; } catch (_) { continue; }
            const variants = [];
            const hls = [];
            const take = (url, type, label) => {
                url = absolutize(url);
                const kind = mediaKindOf(url, type);
                if (kind === "hls") hls.push(url);
                else if (kind === "audio") variants.push({ url, height: 0, audio: true });
                else if (kind === "progressive") variants.push({ url, height: heightFrom(label, url) });
            };
            let srcs;
            try { srcs = pl.currentSources ? pl.currentSources() : null; } catch (_) { srcs = null; }
            let sn = 0;
            try { sn = (srcs && typeof srcs.length === "number") ? srcs.length : 0; } catch (_) { sn = 0; }
            for (let j = 0; j < sn; j++) {
                let s;
                try { s = srcs[j]; } catch (_) { continue; }
                take(readPrim(s, "src"), readPrim(s, "type"), readPrim(s, "label"));
            }
            if (!sn) {
                let one;
                try { one = pl.src ? pl.src() : null; } catch (_) { one = null; }
                take(typeof one === "string" ? one : null, null);
            }
            // Poster from the player config: Video.js exposes poster().
            let img;
            let p;
            try { p = pl.poster ? pl.poster() : null; } catch (_) { p = null; }
            p = absolutize(p);
            if (typeof p === "string" && /^https?:\/\//i.test(p)) img = p;
            // Title: the name the player gives the clip (Brightcove's
            // mediainfo.name — lasprovincias.es runs ten of these on its front
            // page), else the player's element (its own title/aria-label/… or
            // an enclosing figcaption) — the <video> tech first, then the root.
            // Video.js labels every root "Video Player" (localized), which is
            // the player's name for itself, not the clip's.
            let title = null;
            let info = null;
            try { info = pl.mediainfo || null; } catch (_) { info = null; }
            const named = info ? readPrim(info, "name") : null;
            if (typeof named === "string" && named.trim()) title = named.trim().slice(0, 300);
            // The clip's own id (Brightcove's video id): stable across loads,
            // so the background can tell ten players on one page apart.
            let clipId = null;
            const rawId = info ? readPrim(info, "id") : null;
            if ((typeof rawId === "string" && rawId.trim()) || typeof rawId === "number") {
                clipId = String(rawId).trim().slice(0, 100);
            }
            if (!title) {
                try {
                    const root = pl.el ? pl.el() : null;
                    const vEl = root && root.querySelector ? root.querySelector("video, audio") : null;
                    title = domTitleFor(vEl) || configTitleFor(root) || domTitleFor(root);
                } catch (_) { title = null; }
                if (title && isVideoJsSelfLabel(pl, title)) title = null;
            }
            if (variants.length || hls.length) {
                let rootId = null;
                try { rootId = pl.id ? pl.id() : null; } catch (_) { rootId = null; }
                groups.push({ variants, hls, delegates: [], img, title, durationSec: 0, clipId,
                    rootId: typeof rootId === "string" ? rootId : null });
            }
        }
        if (!groups.length) return null;
        log("player-probe: videojs @", location.host, "groups=", groups.length);
        return groups;
    }

    // Browser-shaped navigator hints for a native re-fetch that must look real to
    // a stream CDN's anti-bot. UA verbatim; Accept-Language built WITH q-values
    // ("en-US,ko-KR;q=0.9") — a bare comma-join is a bot tell that once cost a 403
    // (proven on-device: the only header differing from the request that got 200).
    function readNavigatorHints() {
        let ua = "", lang = "";
        try { ua = navigator.userAgent || ""; } catch (_) { ua = ""; }
        try {
            let ls = [];
            if (navigator.languages && navigator.languages.length) ls = navigator.languages;
            else if (navigator.language) ls = [navigator.language];
            if (ls.length) {
                lang = ls[0];
                for (let i = 1, q = 9; i < ls.length && q >= 1; i++, q--) {
                    lang += "," + ls[i] + ";q=0." + q;
                }
            }
        } catch (_) { lang = ""; }
        return { ua, lang };
    }

    // Post an HLS master to background (Java enumerates qualities, no probe). The
    // stream CDN's anti-bot rejects any non-browser-like request, so we send the
    // real UA + q-valued Accept-Language for background.js to rebuild the request.
    // `clipId` names the clip on a page that holds several (the player's own id
    // for it, else its master's host and path): the background otherwise
    // treats every master from one page as the same capture, the page being a
    // single-player embed's identity — ten Brightcove players on a news front
    // page were one capture at most.
    function postHlsMaster(url, title, img, label, siblings, clipId, titleFromPage) {
        const { ua, lang } = readNavigatorHints();
        const payload = { url, origin: location.href, title, img, ua, lang, siblings: siblings || [] };
        if (clipId) payload.clipId = clipId;
        if (titleFromPage) payload.titleFromPage = true;
        log("sending HLS master at", label, title, url.slice(0, 80));
        browser.runtime.sendMessage({ kind: "page-state-hls", payload }).then(() => {}, () => {});
    }

    // ---- Generic page-world player media (ANY site, ANY player) --------------
    // findPlayerMedia (above) reads the resolved URL from a live player API
    // (JWPlayer); this is the GENERAL fallback for players that DON'T expose their
    // fed URL on a readable API — capture WHENEVER a site holds a playable media URL
    // in a page-world JS global before the player fetches it on play — a custom
    // player config (window.page_params, flashvars…), a framework store
    // (__NEXT_DATA__/__NUXT__/Redux…), or any plain object. We walk the page-world
    // state and collect a URL only when it sits under a media-ish KEY
    // (videoUrl/url/src/file/hls/source/contentUrl…) AND its VALUE is a real media
    // URL: the key says "a player", the value extension says "a url to play"
    // (this key-proximity is what keeps us off the page's many non-media URLs —
    // share/canonical/next links never carry a media extension). Three outcomes:
    //   - .m3u8            → HLS master  (postHlsMaster → Java enumerates, no probe)
    //   - .mp4/.m4v/.webm  → progressive variant (page-state-progressive)
    //   - .mp3/.m4a/.aac/… → progressive AUDIO variant (podcast/article audio in
    //                        page state, e.g. podverse) — same emit, no resolution
    //   - a SAME-ORIGIN non-media url beside a format/quality/segmentFormats hint
    //     → a tokenized media-list DELEGATE (e.g. a player whose source is
    //     …/media/mp4/?s=… returning [{quality, videoUrl:…mp4}]); resolved with a
    //     same-origin credentialed fetch (the bridge runs ON the page, so NO host
    //     permission and no CORS problem — that's why the resolve is here, not in
    //     background.js). Generic by SHAPE, never by host. Quality/height comes
    //     from a sibling quality/height/label/resolution field or is parsed from
    //     the URL (…_720P_…); duration from a sibling duration/video_duration.
    // (PROGRESSIVE_RE / HLS_MASTER_RE are defined above, shared with the player-API
    //  reader.)
    // Keys a player holds its source(s) under. The VALUE still has to be a media
    // URL (or a same-origin delegate), so a broad key like `url` is safe. Audio
    // keys (audio_url/enclosure — podcast/article audio in page state, e.g.
    // substack's __NEXT_DATA__ TTS `audio_url`) sit alongside the video ones now
    // that classifyInto accepts audio extensions (AUDIO_RE).
    const MEDIA_KEY_RE = /^(?:video_?url|videourl|audio_?url|audiourl|url|uri|src|source|file|hls|hls_?url|m3u8|dash|manifest_?url|playback_?url|stream_?url|content_?url|media_?url|play_?url|enclosure|enclosure_?url|public_?url|download_?url|asset_?url|secure_?url|mp4_?url|hls_?src|video_?src|audio_?src)$/i;
    // Keys whose value is an ARRAY of quality variants for ONE video (so the whole
    // array is grouped into a single entity, not split). Deliberately narrow —
    // a player's source/quality list, NOT a generic `videos`/`items`/`playlist`
    // (those hold DIFFERENT videos — related/recommended — and must not merge).
    const LIST_KEY_RE = /^(?:media_?definitions|sources|qualities|levels|renditions|formats|variants)$/i;
    // Sibling keys that carry a quality/height hint next to a source URL.
    const QUALITY_KEY_RE = /^(?:quality|height|res|resolution|label)$/i;
    // Sibling keys that mark a media-list entry (so a non-media same-origin URL
    // beside one is treated as a resolvable delegate, not ignored).
    const DELEGATE_HINT_RE = /^(?:format|quality|segment_?formats|defaultquality|remote)$/i;
    // Sibling keys that carry the clip duration (seconds).
    const DURATION_KEY_RE = /^(?:duration|video_?duration|length|seconds)$/i;
    // Sibling keys that carry a poster/thumbnail for the clip.
    const IMG_KEY_RE = /^(?:image_?url|poster|thumb(?:nail)?|cover|preview)$/i;
    // A media-key value that is one of these is never a media delegate (so a
    // same-origin page/asset URL beside a quality hint isn't mistaken for one).
    const NON_MEDIA_EXT_RE = /\.(?:jpe?g|png|gif|webp|svg|ico|css|js|json|html?|xml|woff2?|ttf)(?:[?#]|$)/i;

    // ---- What names a clip in page state -------------------------------------
    // A clip's media sits in an object of its own, and the clip's title beside
    // it or a level or two up: Reuters' front page keeps each clip as
    // {"title":…,"source":{"hls":…master.m3u8}} and twelve clips were posted
    // with the page's title (device audit 2026-10-20); NBC's
    // {"headline":{"primary":…},"videoAssets":[{"publicUrl":…}]}. Each group is
    // named from the walk's path to it: climb from the object that owns the
    // media to the first object with a title, through a list of the clip's
    // RENDITIONS but never into a list of other clips, and never past an object
    // that holds another clip beside this one. Groups whose climb ends at the
    // same object are one clip (ABC News lists a clip's HLS and CMAF masters
    // apart: two rows). The content script reads a page's JSON with the same
    // climb (climbJsonClip there).
    const STATE_TITLE_KEYS = ["title", "headline", "headlines", "videoTitle", "video_title", "episodeTitle",
        "episode_title", "displayTitle", "display_title", "name"];
    const STATE_TITLE_INNER = ["primary", "basic", "main", "default", "text", "title", "value", "web"];
    const STATE_IMAGE_KEYS = ["poster", "posterUrl", "poster_url", "thumbnail", "thumbnailUrl", "thumbnail_url",
        "thumb", "image", "imageUrl", "image_url", "cover", "coverUrl", "primaryImage", "promoImage",
        "promo_image", "artwork", "preview", "previewImage"];
    // Keys only a clip has, never one of its renditions.
    const STATE_CLIP_KEY_RE = /^(?:guid|uuid|slug|poster\w*|thumbnail\w*|thumb|image\w*|cover\w*|description|summary|headlines?|title|name|caption|permalink|share_?url)$/i;
    // Keys a rendition has: its size, encoding, quality.
    const STATE_RENDITION_KEY_RE = /^(?:height|width|bitrate|bandwidth|avg_?bitrate|quality|format|type|mime_?type|container|video_?container|codecs?|label|size|resolution|frame_?height|frame_?width|asset_?type|stream_?type|profile|res|fps)$/i;
    // A list whose name says renditions of one clip.
    const STATE_RENDITION_LIST_RE = /(?:sources|renditions|qualities|formats|variants|levels|assets|streams|files|encodings|bitrates|playlist_?urls|definitions)$/i;
    // Sibling keys naming a FORMAT or quality of the same clip, not another clip.
    const STATE_FORMAT_KEY_RE = /^(?:hls|dash|cmaf|mp4|webm|mp3|m4a|aac|progressive|adaptive|hd|sd|uhd|fhd|high|low|medium|mobile|desktop|default|main|fallback|backup|source|sources|renditions?|formats?|variants?|qualities|streams?|files?|assets?|encodings?|playlists?|manifests?|media|video|audio|urls?|\d{3,4}p?|[48]k)$/i;
    const STATE_QUALITY_RE = /^(?:\d{3,4}p?|[48]k|hd|sd|uhd|fhd|auto|source|original|default|high|low|medium|mobile|desktop)$/i;
    const STATE_MEDIA_VALUE_RE = /\.(?:mp4|m4v|mov|webm|mkv|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|flac|wav)(?:[?#]|$)/i;

    function usableStateTitle(v) {
        if (typeof v !== "string") return "";
        const t = v.replace(/\s+/g, " ").trim();
        if (t.length < 3 || t.length > 300 || !/\p{L}\p{L}/u.test(t)) return "";
        if (/^(?:https?:)?\/\//i.test(t) || STATE_QUALITY_RE.test(t) || STATE_MEDIA_VALUE_RE.test(t)) return "";
        if (/\.(?:jpe?g|png|gif|webp|avif|svg)(?:[?#]|$)/i.test(t)) return "";
        if (!/\s/.test(t) && t.length >= 12 && (t.match(/\d/g) || []).length >= 3) return "";
        if (isSiteNameOnly(t)) return "";
        return t;
    }
    function readObj(o, key) {
        try {
            const v = o[key];
            return v && typeof v === "object" ? v : null;
        } catch (_) { return null; }
    }
    function stateTitleOf(o) {
        for (const k of STATE_TITLE_KEYS) {
            const t = usableStateTitle(readPrim(o, k));
            // A bare `name` is a title only in words: a player's or a file's
            // own name is one token ("heroPlayer").
            if (t && (k !== "name" || /\s/.test(t))) return t;
            const inner = readObj(o, k);
            if (inner && !Array.isArray(inner)) {
                for (const ik of STATE_TITLE_INNER) {
                    const it = usableStateTitle(readPrim(inner, ik));
                    if (it) return it;
                }
            }
        }
        return "";
    }
    // A URL a state value carries: the string, or {url|src|href|primary|…}
    // one or two levels in (NBC: "primaryImage":{"url":{"primary":…}}).
    function stateUrlOf(v, depth) {
        if (typeof v === "string") return v;
        if (!v || typeof v !== "object" || depth > 1) return "";
        for (const k of ["url", "src", "href", "primary", "original", "default", "basic"]) {
            let inner;
            try { inner = v[k]; } catch (_) { continue; }
            const u = stateUrlOf(inner, depth + 1);
            if (u) return u;
        }
        return "";
    }
    function stateImageOf(o) {
        for (const k of STATE_IMAGE_KEYS) {
            let v;
            try { v = o[k]; } catch (_) { continue; }
            const u = stateUrlOf(v, 0);
            if (typeof u !== "string" || !/^(?:https?:)?\/\/|^\/[^/]/i.test(u) || STATE_MEDIA_VALUE_RE.test(u)) continue;
            const abs = absolutize(u);
            if (/^https?:/i.test(abs)) return abs;
        }
        return "";
    }
    // Whether `o` has a key matching `re` with a value. A `name`/`title`/
    // `label` that is a quality ("720p") belongs to a rendition, not a clip.
    function stateHasKey(o, re) {
        const keys = boundedKeys(o);
        if (!keys) return false;
        for (let i = 0; i < keys.length; i++) {
            if (!re.test(keys[i])) continue;
            let v;
            try { v = o[keys[i]]; } catch (_) { continue; }
            if (v === null || v === undefined || v === "" || v === false) continue;
            if (re === STATE_CLIP_KEY_RE && typeof v === "string" && STATE_QUALITY_RE.test(v.trim())) continue;
            return true;
        }
        return false;
    }
    // Whether a state value holds a media URL within two levels.
    function holdsStateMedia(v, depth, budget) {
        if (budget.n-- <= 0 || depth > 2) return false;
        if (typeof v === "string") return STATE_MEDIA_VALUE_RE.test(v) && /^(?:https?:)?\/\/|^\/[^/]/i.test(v);
        if (!v || typeof v !== "object") return false;
        if (Array.isArray(v)) {
            let n = 0;
            try { n = v.length; } catch (_) { return false; }
            for (let i = 0; i < n && i < 20; i++) {
                let e;
                try { e = v[i]; } catch (_) { continue; }
                if (holdsStateMedia(e, depth + 1, budget)) return true;
            }
            return false;
        }
        const keys = boundedKeys(v);
        if (!keys) return false;
        for (let i = 0; i < keys.length && i < 60; i++) {
            let e;
            try { e = v[keys[i]]; } catch (_) { continue; }
            if (holdsStateMedia(e, depth + 1, budget)) return true;
        }
        return false;
    }
    // Whether an object holds ANOTHER clip beside the branch `fromKey`: a
    // sibling, not named as a format of the same clip, with media of its own.
    function holdsOtherStateClip(o, fromKey) {
        const keys = boundedKeys(o);
        if (!keys) return false;
        const budget = { n: 400 };
        for (let i = 0; i < keys.length && i < 200; i++) {
            const k = keys[i];
            if (k === fromKey || STATE_FORMAT_KEY_RE.test(k)) continue;
            let v;
            try { v = o[k]; } catch (_) { continue; }
            if (v && typeof v === "object" && holdsStateMedia(v, 0, budget)) return true;
        }
        return false;
    }
    // Whether the array at path[j] lists renditions of one clip.
    function stateRenditionList(path, j) {
        const from = j + 1 < path.length ? path[j + 1].node : null;
        const key = String(path[j].key);
        if (from && !Array.isArray(from)) {
            if (stateHasKey(from, STATE_CLIP_KEY_RE)) return false;
            return stateHasKey(from, STATE_RENDITION_KEY_RE) || STATE_RENDITION_LIST_RE.test(key);
        }
        return STATE_RENDITION_LIST_RE.test(key);
    }
    // path: [{ node, key }] from the walk's root down to the object owning a
    // group's media. Returns { title, img, anchor }: the anchor is the object
    // the title was read from, else the clip's own object (the first one that
    // is not a rendition), never a parent above it.
    function clipMetaFromPath(path) {
        const info = { title: "", img: "", anchor: null };
        let clipObject = null;
        let objects = 0;
        for (let i = path.length - 1; i >= 0 && objects < 6; i--) {
            const node = path[i].node;
            if (Array.isArray(node)) {
                if (!stateRenditionList(path, i)) break;
                continue;
            }
            if (objects > 0 && holdsOtherStateClip(node, path[i + 1].key)) break;
            objects++;
            const inRenditions = i > 0 && Array.isArray(path[i - 1].node) && stateRenditionList(path, i - 1);
            if (!clipObject && !inRenditions) clipObject = node;
            if (!info.img) info.img = stateImageOf(node);
            const t = stateTitleOf(node);
            if (t) {
                info.title = t;
                info.anchor = node;
                break;
            }
        }
        if (!info.anchor) info.anchor = clipObject || (path.length ? path[path.length - 1].node : null);
        return info;
    }
    // An array whose entries are renditions of one clip, whatever its key is
    // called: every entry an object with a rendition's keys and none of a
    // clip's, at least one with a source URL (NBC's "videoAssets": an HLS
    // master and an mp4 of one clip, each with its format and size).
    // Renditions of one clip differ in a QUALITY or a FORMAT: height, width,
    // bitrate, a quality label, the container (NBC's HLS master beside its
    // mp4). Two entries alike in all of those are two clips (as.com lists its
    // podcast players' configs, {src, type: "audio/mpeg"} each, and two
    // episodes became one tile with one name, device audit 2026-10-20).
    const STATE_QUALITY_DIM_RE = /^(?:height|width|bitrate|bandwidth|avg_?bitrate|quality|res|resolution|frame_?height|frame_?width|profile|fps|definition|label|format|type|mime_?type|container|video_?container|codecs?|stream_?type|asset_?type)$/i;
    const QUALITY_LABEL_RE = /\d{3,4}\s*p\b|\b(?:hd|sd|uhd|fhd|4k|2k|low|medium|high|auto|mobile|desktop)\b/i;
    function qualityOf(e, ek) {
        let out = "";
        for (let j = 0; j < ek.length; j++) {
            if (!STATE_QUALITY_DIM_RE.test(ek[j])) continue;
            const v = readPrim(e, ek[j]);
            if (v == null || typeof v === "object") continue;
            if (/^label$/i.test(ek[j]) && !QUALITY_LABEL_RE.test(String(v))) continue;
            out += ek[j].toLowerCase() + "=" + String(v) + ";";
        }
        return out;
    }

    function looksLikeRenditionList(arr, n) {
        let media = 0;
        const qualities = new Set();
        for (let i = 0; i < n && i < 50; i++) {
            let e;
            try { e = arr[i]; } catch (_) { return false; }
            if (!e || typeof e !== "object" || Array.isArray(e)) return false;
            if (stateHasKey(e, STATE_CLIP_KEY_RE) || !stateHasKey(e, STATE_RENDITION_KEY_RE)) return false;
            const ek = boundedKeys(e);
            if (!ek) return false;
            for (let j = 0; j < ek.length; j++) {
                if (!MEDIA_KEY_RE.test(ek[j])) continue;
                const v = readPrim(e, ek[j]);
                if (typeof v === "string" && /^https?:\/\//i.test(v)) { media++; break; }
            }
            qualities.add(qualityOf(e, ek));
        }
        return media > 0 && (n === 1 || qualities.size > 1);
    }

    // Parse a height (px) from a quality/label value ("720", "720p", "1080P HD")
    // or, failing that, from a media URL (…_720P_… / …/720/…). 0 if unknown.
    function heightFrom(qualityVal, url) {
        if (qualityVal != null) {
            const m = String(qualityVal).match(/(\d{3,4})/);
            if (m) return parseInt(m[1], 10) || 0;
        }
        if (typeof url === "string") {
            const m = url.match(/[_/-](\d{3,4})[pP][_/.-]/) || url.match(/[_/-](\d{3,4})[pP]?(?:[?#]|$)/);
            if (m) return parseInt(m[1], 10) || 0;
        }
        return 0;
    }

    // Read a quality/height hint from any QUALITY_KEY sibling in the same object.
    function siblingQuality(o, keys) {
        for (let i = 0; i < keys.length; i++) {
            if (!QUALITY_KEY_RE.test(keys[i])) continue;
            const v = readPrim(o, keys[i]);
            if (v != null) return v;
        }
        return null;
    }
    // True when the object carries a media-list-entry hint (format/quality/…),
    // qualifying a same-origin non-media value as a resolvable delegate.
    function hasDelegateHint(o, keys) {
        for (let i = 0; i < keys.length; i++) {
            if (DELEGATE_HINT_RE.test(keys[i])) return true;
        }
        return false;
    }

    // Walk a page-world state tree (bounded: depth + node budget + visited set,
    // index-loop / primitive reads only — Xray-safe) and collect playable media as
    // GROUPS — one group per VIDEO, so quality variants of one clip stay together
    // and DIFFERENT clips never merge. A group:
    //   { variants:[{url,height}], hls:[url], delegates:[{url,height}], durationSec, img }
    // Two ways a group forms:
    //   (1) a media-LIST array under a LIST_KEY (sources/mediaDefinitions/…): all
    //       its entries are qualities of ONE clip → one group;
    //   (2) a single player object's own media-key string value(s).
    // NOISE GUARD: (2) is skipped for objects that are ENTRIES OF AN ARRAY — a
    // related/recommended-videos array would otherwise turn every item into its
    // own capture. Such an item is still walked for a nested (1) list, so a main
    // clip carried inside an array still yields its source list. Generic by SHAPE,
    // host-agnostic; runs in every frame (all_frames), reading the player's
    // RESOLVED page-world values — so a packed/eval-obfuscated source URL is read
    // post-resolution, never the packed blob.
    function collectPlayableMedia(root, budgetRef) {
        const seen = new Set();
        // Shared node budget when scanning MANY globals in one pass (readPlayerMedia
        // passes one ref so the whole scan is bounded, not 30k PER global); a lone
        // caller gets its own.
        const budget = budgetRef || { n: 30000 };
        const groups = [];
        let pageOrigin = "";
        try { pageOrigin = location.origin; } catch (_) { pageOrigin = ""; }
        const sameOrigin = (u) => {
            try { return new URL(u, location.href).origin === pageOrigin; } catch (_) { return false; }
        };
        const newGroup = () => ({ variants: [], hls: [], delegates: [], durationSec: 0, img: undefined });
        const nonEmpty = (g) => g.variants.length || g.hls.length || g.delegates.length;

        // Categorise one media-key URL into a group (variant / hls / delegate).
        function classifyInto(g, url, qualityHint, owner, ownerKeys) {
            if (HLS_MASTER_RE.test(url)) { g.hls.push(url); return; }
            if (AUDIO_RE.test(url)) {
                // Audio: no resolution (height 0 — JsonHelper renders no "WxH"),
                // and the mark rides to Java as the variant's audioOnly so the
                // entity types/downloads as AUDIO. Without it the skip-probe
                // variant path stamped video/mp4 on the entity (podverse's
                // __NEXT_DATA__ mp3 showed up as a video).
                g.variants.push({ url, height: 0, audio: true });
                return;
            }
            if (PROGRESSIVE_RE.test(url)) {
                g.variants.push({ url, height: heightFrom(qualityHint, url) });
                return;
            }
            if (sameOrigin(url) && !NON_MEDIA_EXT_RE.test(url)
                && hasDelegateHint(owner, ownerKeys)) {
                g.delegates.push({ url, height: heightFrom(qualityHint, url) });
            }
        }
        // Best-effort duration/poster for a group from the object that owns it.
        function fillMeta(g, o, keys) {
            if (!g.durationSec) {
                for (let i = 0; i < keys.length; i++) {
                    if (!DURATION_KEY_RE.test(keys[i])) continue;
                    const d = Number(readPrim(o, keys[i]));
                    if (isFinite(d) && d > 0 && d < 86400) { g.durationSec = d; break; }
                }
            }
            if (!g.img) {
                for (let i = 0; i < keys.length; i++) {
                    if (!IMG_KEY_RE.test(keys[i])) continue;
                    const iv = readPrim(o, keys[i]);
                    if (typeof iv === "string" && /^https?:\/\//i.test(iv)) { g.img = iv; break; }
                }
            }
        }

        // The walk's path from the root, so each group can be named from the
        // objects above it (clipMetaFromPath).
        const path = [];
        const nameGroup = (g) => {
            const m = clipMetaFromPath(path);
            if (m.title) g.title = m.title;
            if (!g.img && m.img) g.img = m.img;
            g.anchor = m.anchor;
        };

        (function walk(o, depth, fromArray, key) {
            if (!o || typeof o !== "object" || depth > 9 || budget.n-- <= 0) return;
            if (seen.has(o)) return;
            seen.add(o);
            path.push({ node: o, key });
            try {
                walkNode(o, depth, fromArray);
            } finally {
                path.pop();
            }
        })(root, 0, false, null);

        function walkNode(o, depth, fromArray) {
            if (Array.isArray(o)) {
                let n;
                try { n = o.length; } catch (_) { return; }
                if (typeof n !== "number") return;
                for (let i = 0; i < n && i < 500; i++) {
                    let v;
                    try { v = o[i]; } catch (_) { continue; }
                    if (v && typeof v === "object") walkChild(v, depth + 1, true, i);
                }
                return;
            }
            const keys = boundedKeys(o);
            if (!keys) return;

            // (1) media-LIST arrays on this object → one group per list: a
            // list a player names as one (sources, renditions…), or any array
            // whose entries are plainly renditions of one clip.
            for (let ki = 0; ki < keys.length; ki++) {
                let arr;
                try { arr = o[keys[ki]]; } catch (_) { continue; }
                let n = 0;
                try { n = (arr && Array.isArray(arr) && typeof arr.length === "number") ? arr.length : 0; } catch (_) { n = 0; }
                if (!n) continue;
                if (!LIST_KEY_RE.test(keys[ki]) && !looksLikeRenditionList(arr, n)) continue;
                const g = newGroup();
                for (let i = 0; i < n && i < 50; i++) {
                    let e;
                    try { e = arr[i]; } catch (_) { continue; }
                    if (typeof e === "string") {
                        if (/^https?:\/\//i.test(e)) classifyInto(g, e, null, o, keys);
                        continue;
                    }
                    if (!e || typeof e !== "object") continue;
                    seen.add(e); // consumed — don't let it also form a (2) group
                    const ekeys = boundedKeys(e);
                    if (!ekeys) continue;
                    const eq = siblingQuality(e, ekeys);
                    for (let ek = 0; ek < ekeys.length; ek++) {
                        if (!MEDIA_KEY_RE.test(ekeys[ek])) continue;
                        let ev;
                        try { ev = e[ekeys[ek]]; } catch (_) { continue; }
                        if (typeof ev !== "string" || !/^https?:\/\//i.test(ev)) continue;
                        classifyInto(g, ev, eq, e, ekeys);
                    }
                }
                if (nonEmpty(g)) { fillMeta(g, o, keys); nameGroup(g); groups.push(g); }
            }

            // (2) a single player object's own media-key strings → one group.
            // Skipped for array entries (related-list noise guard).
            if (!fromArray) {
                const g = newGroup();
                const q = siblingQuality(o, keys);
                for (let ki = 0; ki < keys.length; ki++) {
                    if (!MEDIA_KEY_RE.test(keys[ki])) continue;
                    let v;
                    try { v = o[keys[ki]]; } catch (_) { continue; }
                    if (typeof v !== "string" || !/^https?:\/\//i.test(v)) continue;
                    classifyInto(g, v, q, o, keys);
                }
                if (nonEmpty(g)) { fillMeta(g, o, keys); nameGroup(g); groups.push(g); }
            }

            // Recurse into child objects/arrays.
            for (let ki = 0; ki < keys.length; ki++) {
                let v;
                try { v = o[keys[ki]]; } catch (_) { continue; }
                if (v && typeof v === "object") walkChild(v, depth + 1, false, keys[ki]);
            }
        }
        function walkChild(v, depth, fromArray, key) {
            if (!v || typeof v !== "object" || depth > 9 || budget.n-- <= 0) return;
            if (seen.has(v)) return;
            seen.add(v);
            path.push({ node: v, key });
            try {
                walkNode(v, depth, fromArray);
            } finally {
                path.pop();
            }
        }

        // Groups whose climb ended at the same object are one clip: its media
        // listed apart (an HLS and a CMAF master, a manifest beside its mp4s).
        const merged = [];
        const byAnchor = new Map();
        for (const g of groups) {
            const into = g.anchor ? byAnchor.get(g.anchor) : null;
            if (!into) {
                if (g.anchor) byAnchor.set(g.anchor, g);
                merged.push(g);
                continue;
            }
            for (const v of g.variants) if (!into.variants.some((x) => x.url === v.url)) into.variants.push(v);
            for (const h of g.hls) if (!into.hls.includes(h)) into.hls.push(h);
            for (const d of g.delegates) if (!into.delegates.some((x) => x.url === d.url)) into.delegates.push(d);
            if (!into.title && g.title) into.title = g.title;
            if (!into.img && g.img) into.img = g.img;
            if (!into.durationSec && g.durationSec) into.durationSec = g.durationSec;
        }
        for (const g of merged) delete g.anchor;
        return merged;
    }

    // Resolve a same-origin JSON delegate to its real media URLs. The page already
    // made this exact fetch on load, so a same-origin credentialed re-fetch is
    // cheap and authenticated. Accepts a top-level array OR an object wrapping one
    // (mediaDefinitions/sources/videos/items/data). Returns [{url, height}].
    async function fetchMediaList(url) {
        let data;
        try {
            const resp = await fetch(url, { credentials: "include", cache: "no-store" });
            if (!resp || !resp.ok) return [];
            const ct = (resp.headers.get("content-type") || "").toLowerCase();
            if (ct && !ct.includes("json") && !ct.includes("text")) return [];
            data = await resp.json();
        } catch (_) { return []; }
        let arr = data;
        if (arr && typeof arr === "object" && typeof arr.length !== "number") {
            arr = data.mediaDefinitions || data.sources || data.videos || data.items
                || data.formats || data.qualities || data.data || [];
        }
        const out = [];
        let n = 0;
        try { n = (arr && typeof arr.length === "number") ? arr.length : 0; } catch (_) { n = 0; }
        for (let i = 0; i < n && i < 50; i++) {
            const it = arr[i];
            if (!it || typeof it !== "object") continue;
            const u = (typeof it.videoUrl === "string" && it.videoUrl)
                || (typeof it.url === "string" && it.url)
                || (typeof it.src === "string" && it.src)
                || (typeof it.file === "string" && it.file);
            if (typeof u !== "string" || !/^https?:\/\//i.test(u)) continue;
            if (AUDIO_RE.test(u)) {
                out.push({ url: u, height: 0, audio: true });
                continue;
            }
            const h = heightFrom(it.quality || it.height || it.label || it.res, u);
            out.push({ url: u, height: h });
        }
        return out;
    }

    // Scan the page-world for playable-media config, NAME-AGNOSTICALLY: walk EVERY
    // enumerable window global object, not a hand-maintained name list — sites put
    // the config under any name (page_params, flashvars_<videoid>, __NEXT_DATA__, a
    // Redux store, a one-off var), and chasing each is the per-site treadmill. This
    // is safe to do broadly because the precision is in collectPlayableMedia's WALK,
    // not the global name: a url only counts when it sits under a media-ish KEY
    // (MEDIA_KEY_RE: videoUrl/url/file/hls/…) with a media-extension VALUE or a
    // same-origin delegate — so arbitrary non-media URLs on unrelated globals are
    // ignored. The known config names are scanned FIRST (common case, found before
    // the budget is spent), then everything else. Bounded three ways: a denylist of
    // huge native window objects, a cap on globals scanned, and a SHARED node budget
    // across the whole pass (so one giant store can't starve the rest, and the total
    // work per page is fixed). Cheap no-op on the ~all pages/frames with no media.
    const PLAYER_MEDIA_GLOBALS = ["page_params", "flashvars"].concat(STATE_GLOBALS);
    const MAX_PLAYER_GROUPS = 40;
    const MAX_GLOBALS_SCANNED = 80;
    const SCAN_NODE_BUDGET = 60000;
    // Native/self-referential window members that are huge or circular — never a
    // media config, and walking them (esp. the DOM) would burn the whole budget.
    const GLOBAL_DENY = new Set([
        "window", "self", "top", "parent", "frames", "globalThis", "document",
        "location", "navigator", "history", "screen", "localStorage",
        "sessionStorage", "indexedDB", "caches", "crypto", "performance",
        "console", "external", "visualViewport", "speechSynthesis",
        "customElements", "trustedTypes", "cookieStore", "scheduler"
    ]);
    function readPlayerMedia() {
        let pw;
        try { pw = window.wrappedJSObject; } catch (_) { pw = null; }
        if (!pw) return null;

        // Candidate names: the known config globals first, then every other own
        // enumerable global, deduped.
        const ordered = [];
        const seenName = new Set();
        const add = (n) => { if (!seenName.has(n)) { seenName.add(n); ordered.push(n); } };
        for (let i = 0; i < PLAYER_MEDIA_GLOBALS.length; i++) add(PLAYER_MEDIA_GLOBALS[i]);
        try {
            const allKeys = Object.keys(pw);
            for (let i = 0; i < allKeys.length; i++) add(allKeys[i]);
        } catch (_) {}

        const budget = { n: SCAN_NODE_BUDGET };
        const groups = [];
        const seenSig = new Set();
        let scanned = 0;
        for (const name of ordered) {
            if (budget.n <= 0 || scanned >= MAX_GLOBALS_SCANNED) break;
            if (GLOBAL_DENY.has(name) || /^(?:on|webkit)/.test(name)) continue;
            let g;
            try { g = pw[name]; } catch (_) { continue; }
            if (!g || typeof g !== "object") continue;
            scanned++;
            let found;
            try { found = collectPlayableMedia(g, budget); } catch (_) { found = null; }
            if (!found) continue;
            for (const grp of found) {
                // Dedup identical groups seen via two globals (a signature of its
                // URL set), and cap the total.
                const sig = grp.variants.map(v => v.url).join("|") + "#"
                    + grp.hls.slice().sort().join("|") + "#"
                    + grp.delegates.map(d => d.url).join("|");
                if (seenSig.has(sig)) continue;
                seenSig.add(sig);
                groups.push(grp);
                if (groups.length >= MAX_PLAYER_GROUPS) return groups;
            }
        }
        return groups.length ? groups : null;
    }

    // Pre-fetch / re-emit guard: don't re-resolve the same delegate set across the
    // retry passes (immediate/DOMContentLoaded/t500/t1500/t4000). Cleared on SPA nav.
    const mediaDefsTried = new Set();

    // Player/embed pages often title the document with a FILENAME-ish string —
    // krakenfiles serves "Embed <name>.mp4". Strip a leading "Embed " (the embed
    // host's own prefix; no real title starts with it) and a trailing media file
    // extension so the entry reads as a title, not a filename. Conservative: only
    // the unambiguous "Embed " verb (not Watch/Play, which can begin real titles).
    function sanitizePlayerTitle(t) {
        if (typeof t !== "string") return t;
        let s = t.trim();
        s = s.replace(/^embed\s+/i, "");
        s = s.replace(/\.(?:mp4|m4v|mkv|avi|webm|mov|ts|flv|wmv|ogv|3gp)$/i, "");
        s = s.trim();
        return s || t;
    }

    // A master URL's stable part — host and path, no scheme, no query — the
    // clip's identity when its player names none (a signed token in the query
    // changes on every load, and an http and an https copy of one master
    // share the rest). Some CDNs sign the PATH instead (Akamai's
    // `/exp=…~hmac=…/`, jwpsrv's `<expiry>_<sig>/`, Kaltura's `/ks/<token>/`):
    // a segment that looks like a signature is left out, or every reload
    // would be a new clip.
    const SIGNED_SEGMENT_RE = /[=~,]|^\d{9,}_|^[A-Za-z0-9_-]{40,}$/;
    function clipKeyOf(url) {
        try {
            const u = new URL(url);
            const path = u.pathname.split("/").filter((seg) => !SIGNED_SEGMENT_RE.test(seg)).join("/");
            return (u.host.toLowerCase() + path).slice(0, 200);
        } catch (_) {
            return null;
        }
    }

    // Emit one group as its own entity: resolve its delegates, then post the
    // progressive variants (page-state-progressive) and/or its HLS master. Title
    // is the group's own, else the generic page title; duration/poster come from
    // the group when present. `multiClip`: this frame holds more than one clip,
    // so a master carries its clip's identity (see postHlsMaster).
    async function emitOneGroup(grp, label, multiClip) {
        const progressive = grp.variants.slice(); // { url, height }
        const hlsSet = new Set(grp.hls);

        if (grp.delegates.length) {
            const preKey = grp.delegates.map(d => d.url).join("|");
            if (!mediaDefsTried.has(preKey)) {
                mediaDefsTried.add(preKey);
                for (const d of grp.delegates) {
                    const list = await fetchMediaList(d.url);
                    for (const item of list) {
                        if (HLS_MASTER_RE.test(item.url)) hlsSet.add(item.url);
                        else if (PROGRESSIVE_RE.test(item.url) || AUDIO_RE.test(item.url)) progressive.push(item);
                    }
                }
            }
        }

        const meta = resolveMeta(null); // generic og:/title + og:image
        // A player-API item can carry its own title and poster; prefer them over
        // the page's. The page's og:image came first here, which gave every
        // clip on a page the same picture (a news front page's is its logo).
        const ownTitle = typeof grp.title === "string" && grp.title.trim() ? grp.title : "";
        const title = sanitizePlayerTitle(ownTitle || meta.title);
        // The page's title stands in for a clip nothing here names: the
        // background then asks the frame's content script, whose card walk
        // and page-JSON reading can name it (titleFromPage).
        const titleFromPage = !ownTitle;
        const img = grp.img || meta.img;
        const durationMs = grp.durationSec > 0 ? Math.round(grp.durationSec * 1000) : 0;

        // ONE ENTITY PER GROUP. A group is one clip; a player that holds both an
        // HLS master and progressive renditions (JW Platform: master + mp4
        // ladder + m4a; Brightcove: pmp4 renditions + hls/dash masters) used to
        // emit BOTH a progressive entity and a master entity — the same video
        // twice in the Captured sheet, each with its own quality picker over
        // the same ladder (on-device, lasprovincias.es). The master wins when
        // present: Java enumerates its full ladder, and an HLS download remuxes
        // to the same mp4 a progressive rendition would give; a lone mp4
        // beside a master is usually the low fallback, so preferring it could
        // lose the higher rungs. Everything folded away rides along as
        // `siblings` so the background CLAIMS those URLs for this clip
        // (requests.js claimPlayerMedia) — the player fetching one on play, or
        // the content script scraping it off the embed's og:video, is then
        // dropped instead of becoming a second row. A progressive-only group
        // (series.ly/krakenfiles) and an HLS-only one (vibuxer/luluvdo) are
        // unchanged.
        const siblings = [];
        for (const v of progressive) if (!siblings.includes(v.url)) siblings.push(v.url);
        for (const m of hlsSet) if (!siblings.includes(m)) siblings.push(m);
        if (hlsSet.size && progressive.length) {
            log("folding", progressive.length, "progressive rendition(s) into the HLS master entity at", label);
            progressive.length = 0;
        }

        if (progressive.length) {
            const byUrl = new Map();
            for (const v of progressive) { if (!byUrl.has(v.url)) byUrl.set(v.url, v); }
            const variants = Array.from(byUrl.values())
                .sort((a, b) => (b.height || 0) - (a.height || 0))
                .map(v => {
                    const out = { url: v.url, width: 0, height: v.height || 0 };
                    // Declared audio (AUDIO_RE / <audio> element / audio MIME):
                    // JsonHelper.parseVariants reads audioOnly and the skip-probe
                    // variant path keeps the entity audio-typed (raw mp3 via
                    // HttpDownloadStrategy) instead of stamping video/mp4.
                    if (v.audio) out.audioOnly = true;
                    return out;
                });
            if (variants.length && !sentKeys.has(variants[0].url)) {
                sentKeys.add(variants[0].url);
                // Navigator hints so the native download can replicate the
                // browser's <video>-element request: some progressive CDNs
                // (krakencloud /play/video/<token> on series.ly) 404 a bare GET
                // and only serve a media-element-shaped request (UA + Accept:
                // video/* + Accept-Language + Sec-Fetch-Dest: video). background
                // builds those from ua/lang here.
                const { ua, lang } = readNavigatorHints();
                const payload = { variants, origin: location.href, title, img, durationMs, ua, lang, siblings };
                if (titleFromPage) payload.titleFromPage = true;
                log("sending", variants.length, "page-player progressive variant(s) at", label, title);
                browser.runtime.sendMessage({ kind: "page-state-progressive", payload }).then(() => {}, () => {});
            }
        }
        // ONE master per group too: Brightcove lists each clip's master as HLS
        // and DASH, over http and https, and they used to be posted one after
        // another for the background to collapse onto whichever came first —
        // a DASH .mpd too, which the native HLS enumeration cannot read. An
        // HLS playlist first, then https; the rest are siblings.
        const masterRank = (m) => (HLS_MASTER_RE.test(m) ? 2 : 0) + (/^https:/i.test(m) ? 1 : 0);
        let master = null;
        for (const m of hlsSet) {
            if (!master || masterRank(m) > masterRank(master)) master = m;
        }
        let masterSent = false;
        for (const m of hlsSet) {
            if (sentKeys.has(m)) masterSent = true;
        }
        // A master whose stable key this page state already posted is the same
        // clip re-signed (a player refreshing its token): not a new capture.
        const key = clipKeyOf(master);
        if (master && !masterSent && key && postedClipKeys.has(key)) {
            for (const m of hlsSet) sentKeys.add(m);
            masterSent = true;
        }
        if (master && !masterSent) {
            for (const m of hlsSet) sentKeys.add(m);
            // The clip's identity: the player's own id for it, else, when this
            // frame holds several clips OR this page state already posted a
            // different master (a single player that moved on to the next clip
            // of a playlist), the master's stable key. Keyed on the page alone,
            // the next clip was dropped as the first one again — and claimed,
            // so the play-time capture was refused too.
            const clipId = grp.clipId || ((multiClip || postedClipKeys.size > 0) ? key : null);
            if (key) postedClipKeys.add(key);
            postHlsMaster(master, title, img, label, siblings, clipId, titleFromPage);
        }
    }

    function mediaElementCount() {
        try { return document.querySelectorAll("video, audio").length; } catch (_) { return 0; }
    }

    async function resolveAndEmitPlayerMedia(groups, label) {
        const multiClip = groups.length > 1 || mediaElementCount() > 1;
        for (const grp of groups) {
            try { await emitOneGroup(grp, label, multiClip); } catch (_) {}
        }
        return true;
    }

    // ---- Mega.nz links (folder, single file, embed) --------------------------
    // Mega is zero-knowledge: the decryption key lives in the URL fragment (after
    // #), which is NEVER sent to any server — so neither the wire nor the DOM can
    // see it, only page-world. We don't even need wrappedJSObject here: the handle
    // is in the path and the key is in location.hash. We hand both to the
    // background, which talks to Mega's API natively (anonymous cs `f`/`g`) and
    // emits entities; the file bytes are AES-CTR ciphertext on the wire, so the
    // generic catcher can't produce a usable download — the native MegaStrategy
    // decrypts on download instead.
    //
    // Shapes handled (modern + legacy):
    //   folder: /folder/<h>#<key>[/folder|/file/<sub>]   |  #F!<h>!<key>
    //   file:   /file/<h>#<key>                           |  #!<h>!<key>
    //   embed:  /embed/<h>#<key>  (a single file in a cross-origin iframe — the
    //           bridge reaches it because it runs all_frames)
    // A folder key is 128-bit (~22 base64url chars); a file key is 256-bit (~43).
    // The first hash segment is the key; anything after a '/' is in-app nav state.
    function parseMegaLink(pathname, rawHash) {
        let hash = (rawHash || "").replace(/^#/, "");
        // Legacy: #F!<h>!<key> (folder) / #!<h>!<key> (file), possibly under /embed.
        if (hash[0] === "F" && hash[1] === "!") {
            const p = hash.slice(2).split("!");
            if (p[0] && p[1]) return { kind: "folder", handle: p[0], key: p[1] };
        }
        if (hash[0] === "!") {
            const p = hash.slice(1).split("!");
            if (p[0] && p[1]) return { kind: "file", handle: p[0], key: p[1] };
        }
        // Modern path-based forms.
        let m;
        if ((m = /^\/folder\/([0-9A-Za-z_-]+)/.exec(pathname))) {
            return { kind: "folder", handle: m[1], key: hash.split("/")[0] };
        }
        if ((m = /^\/(?:file|embed)\/([0-9A-Za-z_-]+)/.exec(pathname))) {
            return { kind: "file", handle: m[1], key: hash.split("/")[0] };
        }
        return null;
    }

    function extractMega() {
        let host;
        try { host = location.hostname; } catch (_) { return false; }
        if (!/(?:^|\.)mega\.(nz|co\.nz)$/i.test(host)) return false;

        let link;
        try { link = parseMegaLink(location.pathname || "", location.hash || ""); } catch (_) { link = null; }
        if (!link || !link.handle || !link.key) return false;
        // Reject the wrong-size key for the link kind (a folder key is ~22 chars,
        // a file key ~43) so a half-loaded URL doesn't emit a doomed capture.
        const minLen = link.kind === "folder" ? 16 : 40;
        if (link.key.length < minLen) return false;

        const dedupKey = "mega:" + link.kind + ":" + link.handle;
        if (sentKeys.has(dedupKey)) { armSpaObserver(); return true; }
        sentKeys.add(dedupKey);

        const title = (document.title || "").split(/\s[-|]\s/)[0].trim();
        const img = ogMeta("og:image") || undefined;

        if (link.kind === "folder") {
            const folderPage = location.origin + "/folder/" + link.handle;
            const payload = {
                folderHandle: link.handle,
                masterKey: link.key,
                origin: folderPage, // clean page URL, no key fragment
                title: title || "Mega folder",
                img
            };
            log("sending mega folder", link.handle, "key", link.key.length, "chars");
            browser.runtime.sendMessage({ kind: "mega-folder", payload }).then(() => {}, () => {});
        } else {
            const filePage = location.origin + "/file/" + link.handle;
            const payload = {
                fileHandle: link.handle,
                fileKey: link.key,
                origin: filePage,
                title: title || undefined, // real name comes from the native attr fetch
                img
            };
            log("sending mega file", link.handle, "key", link.key.length, "chars");
            browser.runtime.sendMessage({ kind: "mega-file", payload }).then(() => {}, () => {});
        }
        armSpaObserver();
        return true;
    }

    // ---- Emit + lifecycle ----------------------------------------------------
    const sentKeys = new Set();
    // Stable keys (clipKeyOf) of the masters this page state posted; bounded
    // by the masters a page holds, cleared with sentKeys on an SPA navigation.
    const postedClipKeys = new Set();
    let spaArmed = false;
    let loggedFrame = false;

    // `final` = this is the LAST pass that will run for this page state, so an
    // unsettled title must no longer hold the emit back (t4000, and any SPA
    // re-read, which is debounced and schedules no further retry).
    function extractAndSend(label, final) {
        // One line per frame (after DEBUG resolves) so you can confirm the bridge
        // is actually running INSIDE the player iframe — i.e. all_frames took and
        // the version bump re-registered. If you never see the player iframe's
        // host here, the bridge isn't reaching it (re-register / all_frames issue).
        if (!loggedFrame) {
            loggedFrame = true;
            log("bridge active @", location.host, "top=" + (window === window.top), "label=" + label);
        }
        // 0) Mega.nz folder / file / embed link — key is in the URL fragment,
        //    page-world only (neither wire nor DOM can see it).
        if (extractMega()) return true;

        // 1) SSR-inlined DASH slice (bilibili.tv etc.) → video+audio variants.
        const state = readPageState();
        if (state) {
            const built = buildVariants(cloneDash(state.dash));
            if (built) {
                // Dedup so retries / SPA re-reads don't re-emit the same set.
                const key = built.variants.map(v => v.url).join("|");
                if (sentKeys.has(key)) { armSpaObserver(); return true; }
                const meta = resolveMeta(state.root);
                /* Hold back a capture whose title is still just the site name.
                 * The repository is FIRST-CAPTURE-WINS with no metadata merge,
                 * so emitting now would permanently name the entry "bilibili"
                 * and the later retry pass — which has the real title — would be
                 * dropped as a duplicate. The state is already there, so simply
                 * let a later pass send it; on the final pass we emit regardless
                 * rather than lose the capture over a name. Note `sentKeys` is
                 * added to only once we actually send, so the deferral does not
                 * mark this set as done. */
                if (meta.titleIsSiteName && !final) {
                    log("deferring", built.variants.length, "variant(s) at", label,
                        "— title is still the site name:", meta.title);
                    return false;
                }
                sentKeys.add(key);
                const payload = {
                    variants: built.variants,
                    origin: location.href,
                    title: meta.title,
                    img: meta.img,
                    durationMs: built.durationMs
                };
                log("sending", built.variants.length, "variant(s) at", label, payload.title);
                browser.runtime.sendMessage({ kind: "page-state-media", payload }).then(() => {}, () => {});
                armSpaObserver();
                return true;
            }
        }

        // 2) READ THE URL THE PLAYER WAS FED — player-AGNOSTIC (DOM <video>/<source>
        //    + JWPlayer + Video.js, see findPlayerMedia). The resolved source, so we
        //    capture without the user pressing play and read it AFTER any eval/
        //    packer de-obfuscation. Covers an HLS master AND progressive (incl. an
        //    extensionless type="video/*" source — series.ly/krakenfiles via Plyr).
        //    The played URL dedups by URL against this; raw .ts segments are dropped
        //    natively (mpegts).
        const playerMedia = findPlayerMedia();
        if (playerMedia) {
            resolveAndEmitPlayerMedia(playerMedia, label).then(() => {}, () => {});
            armSpaObserver();
            return true;
        }

        // 3) GENERIC page-world player media (any site, any player) for players
        //    whose fed value is NOT a final URL on a readable API but a config/
        //    source-list in a page-world global (or a tokenized JSON delegate that
        //    only resolves to a URL after a fetch). Direct .mp4/.m3u8
        //    under a media-ish key, a source list, or a same-origin delegate
        //    (resolved with a same-origin fetch). Async; fire-and-forget.
        const found = readPlayerMedia();
        if (found) {
            resolveAndEmitPlayerMedia(found, label).then(() => {}, () => {});
            armSpaObserver();
            return true;
        }
        return false;
    }

    // SPA episode navigation swaps the page model without a document reload.
    // Only armed once we've actually seen inlined media, so the ~all sites that
    // have none never pay for a persistent subtree observer.
    function armSpaObserver() {
        if (spaArmed) return;
        spaArmed = true;
        let lastUrl = location.href;
        let debounce = null;
        new MutationObserver(() => {
            if (location.href === lastUrl) return;
            lastUrl = location.href;
            sentKeys.clear();
            postedClipKeys.clear();
            mediaDefsTried.clear();
            lateReads = 0;
            clearTimeout(debounce);
            debounce = setTimeout(() => extractAndSend("spa", true), 600);
        }).observe(document.documentElement, { childList: true, subtree: true });
    }

    // A player can be set up long after the timed passes below: lasprovincias.es
    // loads its ten Brightcove players only once the cookie consent resolves
    // and the slider scrolls into view, 8-13 s after load (HAR 26-10-19), and
    // the t4000 pass had long gone, so none of them was ever read. Setting a
    // source on a media element fires `loadstart` at it — whatever the preload
    // setting, and for an MSE blob as for a file — after the player recorded
    // the source it chose, so a later read finds it. The event does not
    // bubble; a capturing listener on the document sees every element's.
    // A FIXED window, not a trailing debounce: the first event arms one read
    // and the events inside its window ride on it. A timer reset on every
    // event never fired on a page that keeps starting media (an autoplaying
    // feed, an ad slot cycling clips), which is exactly where players keep
    // arriving. The first LATE_READ_BURST reads come at the window's pace (a
    // page's players start together); after that one read per
    // LATE_READ_SLOW_MS for as long as media keeps starting. A hard stop at
    // twelve lost every player set up later on a long page (a live blog that
    // builds each embed as it scrolls into view).
    const LATE_READ_DEBOUNCE_MS = 250;
    const LATE_READ_BURST = 12;
    const LATE_READ_SLOW_MS = 5000;
    let lateReads = 0;
    let lateTimer = null;
    let timedPassesDone = false;
    function onMediaLoadStart() {
        if (lateTimer) return;
        const delay = lateReads < LATE_READ_BURST ? LATE_READ_DEBOUNCE_MS : LATE_READ_SLOW_MS;
        lateTimer = setTimeout(() => {
            lateTimer = null;
            lateReads++;
            // Final only once the timed passes are over: before t4000 a
            // site-name title may still hold an SSR set back for a later pass
            // (the deferral in extractAndSend); first-capture-wins would keep it.
            extractAndSend("loadstart", timedPassesDone);
        }, delay);
    }
    try { document.addEventListener("loadstart", onMediaLoadStart, true); } catch (_) {}

    // State lands during page JS execution; try now, at DOMContentLoaded, and a
    // couple of short retries. Each is a cheap no-op when no media state exists.
    extractAndSend("immediate");
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", () => extractAndSend("DOMContentLoaded"), { once: true });
    }
    setTimeout(() => extractAndSend("t500"), 500);
    setTimeout(() => extractAndSend("t1500"), 1500);
    // A JS player's setup() can lag the page load, so one later attempt — a cheap
    // no-op in the ~all frames (incl. ad iframes) that have no player global.
    setTimeout(() => {
        timedPassesDone = true;
        extractAndSend("t4000", true);
    }, 4000);
})();
