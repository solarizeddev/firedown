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
                for (let i = 0; i < n && !found; i++) {
                    let v;
                    try { v = o[i]; } catch (_) { continue; }
                    walk(v, depth + 1);
                }
                return;
            }
            let keys;
            try { keys = Object.keys(o); } catch (_) { return; }
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
    function pageTitle() {
        const og = ogMeta("og:title");
        if (og) return og;
        const t = (document.title || "").split(/\s[-|]\s/)[0].trim();
        return t || location.host || "video";
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
        return {
            title: (rich && rich.title) || pageTitle(),
            img: (rich && rich.img) || ogMeta("og:image") || undefined
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
    // <video>/<source> the player drives) as "hls" | "progressive". The url IS
    // media by definition — the player was told to play it — so we DELIBERATELY DO
    // NOT gate on the file extension: these embeds keep serving the manifest at an
    // obfuscated / rotating extension (series.ly/vibuxer's HLS master is at
    // /master.txt, and that will keep changing). We decide the emit path from the
    // player's DECLARED type — its stable, structural signal (JWPlayer "hls", a
    // video/audio MIME, application/x-mpegurl, dash) — then a .m3u8/.mpd hint, and
    // finally DEFAULT TO HLS: an unknown source on these players is an obfuscated
    // master, and the HLS path sends the full anti-bot header set AND native
    // reroutes a non-manifest body to ffmpeg, whereas the progressive path sends no
    // headers and would 403 a header-gated master. Only a non-http (blob:/data:/
    // mediasource:) MSE handle returns null — that real manifest is read from the
    // player API. (The broad page-state walk does NOT use this — it stays
    // extension-strict so it can't grab a non-media URL that merely sits under a
    // media-ish key.)
    function mediaKindOf(url, type) {
        if (typeof url !== "string" || !/^https?:\/\//i.test(url)) return null;
        const ty = (typeof type === "string") ? type.toLowerCase().trim() : "";
        if (/mpegurl|m3u8|^hls$|^dash$|dash\+xml|mpd/.test(ty)) return "hls";
        if (/^(?:video|audio)\//.test(ty) || /^(?:mp4|m4v|webm|mov|ogv|ogg|aac|mp3)$/.test(ty)) return "progressive";
        if (HLS_MASTER_RE.test(url) || /\.mpd(?:[?#]|$)/i.test(url)) return "hls";
        if (PROGRESSIVE_RE.test(url)) return "progressive";
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
        const take = (url, label, type) => {
            url = absolutize(url);
            const kind = mediaKindOf(url, type);
            if (kind === "hls") hls.push(url);
            else if (kind === "progressive") variants.push({ url, height: heightFrom(label, url) });
        };
        // The item's own resolved `file`, then each entry of `sources` (qualities).
        take(readPrim(item, "file"), readPrim(item, "label") || readPrim(item, "height"), readPrim(item, "type"));
        let sources;
        try { sources = item.sources; } catch (_) { sources = null; }
        let n = 0;
        try { n = (sources && typeof sources.length === "number") ? sources.length : 0; } catch (_) { n = 0; }
        for (let i = 0; i < n; i++) {
            let s;
            try { s = sources[i]; } catch (_) { continue; }
            take(readPrim(s, "file"), readPrim(s, "label") || readPrim(s, "height") || readPrim(s, "quality"), readPrim(s, "type"));
        }
        if (!variants.length && !hls.length) return null;
        const t = readPrim(item, "title");
        const d = Number(readPrim(item, "duration"));
        // Poster from the player config: JWPlayer item carries `image`.
        const image = readPrim(item, "image") || readPrim(item, "poster");
        return {
            variants, hls, delegates: [],
            img: (typeof image === "string" && /^https?:\/\//i.test(image)) ? image : undefined,
            title: (typeof t === "string" && t.trim()) ? t.trim() : null,
            durationSec: (isFinite(d) && d > 0) ? d : 0
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
        // The DOM read runs only in a frame that actually looks like a player/embed
        // (a known player global or a player container) — the bridge runs in EVERY
        // frame on EVERY site, so an ungated <video> scan would capture inline
        // article videos site-wide. The API readers are self-gating (the global
        // must exist), so they always run.
        if (looksLikePlayerFrame()) {
            const dom = readDomMedia(); // array of per-element groups | null
            if (dom) for (let i = 0; i < dom.length; i++) groups.push(dom[i]);
        }
        const jw = readJwPlayer(); // single group | null
        if (jw) groups.push(jw);
        const vjs = readVideoJs(); // array of per-player groups | null
        if (vjs) for (let i = 0; i < vjs.length; i++) groups.push(vjs[i]);
        return groups.length ? groups : null;
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
            if (document.querySelector(".plyr,.video-js,.jwplayer,.jw-video,.fp-player,[data-plyr],[data-player]")) return true;
        } catch (_) {}
        return false;
    }

    // A per-clip title from the EXPLICIT, author-provided signals on/around a
    // media element — NOT a guess from nearby headings (noisy on real pages). In
    // order: the element's own title / aria-label / data-title(-video-title), an
    // aria-labelledby target's text, then an enclosing <figure>'s <figcaption>.
    // Returns a trimmed, length-capped string or null (caller falls back to the
    // page title). Pure DOM reads — safe on an Xray-wrapped element.
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
    // or null. (The module-level sentKeys guard in emitOneGroup still collapses a
    // url seen by two readers — e.g. DOM + Video.js on the same element.)
    function readDomMedia() {
        let els;
        try { els = document.querySelectorAll("video, audio"); } catch (_) { return null; }
        const n = els ? els.length : 0;
        if (!n) return null;

        const groups = [];
        for (let i = 0; i < n; i++) {
            const el = els[i];
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
                else if (kind === "progressive") variants.push({ url, height: heightFrom(info.label, url) });
            }
            if (variants.length || hls.length) {
                groups.push({ variants, hls, delegates: [], img, title: domTitleFor(el), durationSec: 0 });
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
            if (grp) return grp;
        }
        log("player-probe: no playable source in jw playlist");
        return null;
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
            // Title from the player's element (its own title/aria-label/… or an
            // enclosing figcaption) — the <video> tech first, then the root.
            let title = null;
            try {
                const root = pl.el ? pl.el() : null;
                const vEl = root && root.querySelector ? root.querySelector("video, audio") : null;
                title = domTitleFor(vEl) || domTitleFor(root);
            } catch (_) { title = null; }
            if (variants.length || hls.length) {
                groups.push({ variants, hls, delegates: [], img, title, durationSec: 0 });
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
    function postHlsMaster(url, title, img, label) {
        const { ua, lang } = readNavigatorHints();
        const payload = { url, origin: location.href, title, img, ua, lang };
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
    // URL (or a same-origin delegate), so a broad key like `url` is safe.
    const MEDIA_KEY_RE = /^(?:video_?url|videourl|url|uri|src|source|file|hls|hls_?url|m3u8|dash|manifest_?url|playback_?url|stream_?url|content_?url|media_?url|play_?url)$/i;
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
            if (PROGRESSIVE_RE.test(url) || AUDIO_RE.test(url)) {
                // Audio has no resolution → heightFrom returns 0, which the emit
                // renders as a no-resolution variant (JsonHelper shows no "WxH").
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

        (function walk(o, depth, fromArray) {
            if (!o || typeof o !== "object" || depth > 9 || budget.n-- <= 0) return;
            if (seen.has(o)) return;
            seen.add(o);

            if (Array.isArray(o)) {
                let n;
                try { n = o.length; } catch (_) { return; }
                if (typeof n !== "number") return;
                for (let i = 0; i < n && i < 500; i++) {
                    let v;
                    try { v = o[i]; } catch (_) { continue; }
                    if (v && typeof v === "object") walk(v, depth + 1, true);
                }
                return;
            }
            let keys;
            try { keys = Object.keys(o); } catch (_) { return; }

            // (1) media-LIST arrays on this object → one group per list.
            for (let ki = 0; ki < keys.length; ki++) {
                if (!LIST_KEY_RE.test(keys[ki])) continue;
                let arr;
                try { arr = o[keys[ki]]; } catch (_) { continue; }
                let n = 0;
                try { n = (arr && typeof arr.length === "number") ? arr.length : 0; } catch (_) { n = 0; }
                if (!n) continue;
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
                    let ekeys;
                    try { ekeys = Object.keys(e); } catch (_) { continue; }
                    const eq = siblingQuality(e, ekeys);
                    for (let ek = 0; ek < ekeys.length; ek++) {
                        if (!MEDIA_KEY_RE.test(ekeys[ek])) continue;
                        let ev;
                        try { ev = e[ekeys[ek]]; } catch (_) { continue; }
                        if (typeof ev !== "string" || !/^https?:\/\//i.test(ev)) continue;
                        classifyInto(g, ev, eq, e, ekeys);
                    }
                }
                if (nonEmpty(g)) { fillMeta(g, o, keys); groups.push(g); }
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
                if (nonEmpty(g)) { fillMeta(g, o, keys); groups.push(g); }
            }

            // Recurse into child objects/arrays.
            for (let ki = 0; ki < keys.length; ki++) {
                let v;
                try { v = o[keys[ki]]; } catch (_) { continue; }
                if (v && typeof v === "object") walk(v, depth + 1, false);
            }
        })(root, 0, false);

        return groups;
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
    const MAX_PLAYER_GROUPS = 12;
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

    // Emit one group as its own entity: resolve its delegates, then post the
    // progressive variants (page-state-progressive) and/or HLS master(s). Title is
    // the generic page title; duration/poster come from the group when present.
    async function emitOneGroup(grp, label) {
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
        // A player-API item can carry its own title; prefer it over the page title.
        const title = sanitizePlayerTitle((typeof grp.title === "string" && grp.title) ? grp.title : meta.title);
        const img = meta.img || grp.img;
        const durationMs = grp.durationSec > 0 ? Math.round(grp.durationSec * 1000) : 0;

        if (progressive.length) {
            const byUrl = new Map();
            for (const v of progressive) { if (!byUrl.has(v.url)) byUrl.set(v.url, v); }
            const variants = Array.from(byUrl.values())
                .sort((a, b) => (b.height || 0) - (a.height || 0))
                .map(v => ({ url: v.url, width: 0, height: v.height || 0 }));
            if (variants.length && !sentKeys.has(variants[0].url)) {
                sentKeys.add(variants[0].url);
                // Navigator hints so the native download can replicate the
                // browser's <video>-element request: some progressive CDNs
                // (krakencloud /play/video/<token> on series.ly) 404 a bare GET
                // and only serve a media-element-shaped request (UA + Accept:
                // video/* + Accept-Language + Sec-Fetch-Dest: video). background
                // builds those from ua/lang here.
                const { ua, lang } = readNavigatorHints();
                const payload = { variants, origin: location.href, title, img, durationMs, ua, lang };
                log("sending", variants.length, "page-player progressive variant(s) at", label, title);
                browser.runtime.sendMessage({ kind: "page-state-progressive", payload }).then(() => {}, () => {});
            }
        }
        for (const masterUrl of hlsSet) {
            if (sentKeys.has(masterUrl)) continue;
            sentKeys.add(masterUrl);
            postHlsMaster(masterUrl, title, img, label);
        }
    }

    async function resolveAndEmitPlayerMedia(groups, label) {
        for (const grp of groups) {
            try { await emitOneGroup(grp, label); } catch (_) {}
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
    let spaArmed = false;
    let loggedFrame = false;

    function extractAndSend(label) {
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
                sentKeys.add(key);
                const meta = resolveMeta(state.root);
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
            mediaDefsTried.clear();
            clearTimeout(debounce);
            debounce = setTimeout(() => extractAndSend("spa"), 600);
        }).observe(document.documentElement, { childList: true, subtree: true });
    }

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
    setTimeout(() => extractAndSend("t4000"), 4000);
})();
