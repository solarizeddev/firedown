// Telegram (t.me) parser — public channel/group post pages.
// ----------------------------------------------------------------------------
// A public post URL (https://t.me/<channel>/<id>) is a server-rendered HTML
// page: Telegram inlines the post's media + metadata directly in the main_frame
// document — there is NO playurl XHR and NO page-world JS state to read (the
// page is plain SSR HTML, not an SPA), so the wire/DOM/page-state sources never
// see a rich capture. We read the post straight from the raw main_frame response
// with filterResponseData (the Threads "read the network response, not the DOM"
// pattern — though here it's for a different reason: the data isn't consumed out
// of the DOM, it's just simplest to pull from the one HTML response we already
// get rather than wait on the content script).
//
// Where the media lives in the page (confirmed against the tgme widget markup):
//   - <meta property="og:video" content="https://cdn4.cdn-telegram.org/file/…mp4">
//     (+ og:video:width/height) — the canonical single-video source.
//   - <video class="tgme_widget_message_video …" src="https://…mp4"> — one per
//     clip, so an ALBUM post (several grouped videos) exposes several <video>s
//     the single og:video can't represent. We scan all of them and fall back to
//     og:video when the page carries no <video src> (e.g. a poster-only,
//     click-to-load variant).
//   - <time class="message_video_duration …">M:SS</time> — per-clip duration,
//     paired with the <video>s by document order, so the parser supplies
//     url + duration and sendVariants skips the capture-time ffmpeg probe.
//   - og:title = channel name, og:description = message text, og:image = poster.
//
// The media is a progressive MP4 (Telegram has no HLS on t.me previews) on a
// signed, self-authorizing CDN URL (cdn*.cdn-telegram.org / cdn*.telesco.pe),
// so a single-URL variant downloads byte-exact via HttpDownloadStrategy — no
// manifest, no separate audio. The same URL is what the player fetches on play,
// so the parser-blocklist entry (cardinal rule) keeps the generic catcher off
// it; see parser-blocklist.js `telegram`.
// ============================================================================
import { log, sendVariants, cacheTabUrl, readFilteredBody, tryParseJson } from './common.js';

// Match a public post: t.me/<channel>/<id> (and the /s/ web-preview variant),
// channel = [A-Za-z0-9_]. Query tails (?embed=1 / ?single) are ignored. A bare
// channel feed (no numeric id) or non-post paths (joinchat/addstickers/…) don't
// match, so the doc filter is armed only on an actual post.
const TELEGRAM_POST_RE = /^https?:\/\/t\.me\/(?:s\/)?([A-Za-z0-9_]+)\/(\d+)/;

// og: meta content, tolerant of either attribute order
// (property-then-content or content-then-property).
function metaContent(html, prop) {
    let m = html.match(new RegExp('<meta[^>]+property=["\']' + prop + '["\'][^>]*\\bcontent=["\']([^"\']*)["\']', 'i'));
    if (m) return m[1];
    m = html.match(new RegExp('<meta[^>]+content=["\']([^"\']*)["\'][^>]*\\bproperty=["\']' + prop + '["\']', 'i'));
    return m ? m[1] : null;
}

// Collect every <video> tag's src (one per clip in an album post). Telegram's
// message videos are progressive .mp4 on its CDN; filter to .mp4 so a stray
// non-media <video> can't sneak in.
function collectVideoSrcs(html) {
    const out = [];
    const tagRe = /<video\b[^>]*>/gi;
    let m;
    while ((m = tagRe.exec(html)) !== null) {
        const src = (m[0].match(/\bsrc=["']([^"']+)["']/) || [])[1];
        if (src && /\.mp4(?:[?#]|$)/i.test(src)) out.push(src);
    }
    return out;
}

// Per-clip durations from the widget's <time class="…message_video_duration…">,
// document-ordered to line up with collectVideoSrcs.
function collectDurations(html) {
    const out = [];
    const re = /<time\b[^>]*message_video_duration[^>]*>([^<]+)<\/time>/gi;
    let m;
    while ((m = re.exec(html)) !== null) out.push(parseClock(m[1].trim()));
    return out;
}

// "M:SS" / "H:MM:SS" → seconds. Returns 0 on anything unparseable (sendVariants
// then probes for the duration instead of trusting a bad value).
function parseClock(s) {
    if (!/^\d{1,2}(:\d{1,2}){1,2}$/.test(s)) return 0;
    let total = 0;
    for (const part of s.split(':')) total = total * 60 + parseInt(part, 10);
    return Number.isFinite(total) ? total : 0;
}

// The embed widget iframe carries NO og: tags (those are on the landing page),
// so when we capture from the iframe the title/author/thumbnail must come from
// the tgme widget markup itself. The /s/ web-preview form has the SAME markup in
// its main_frame, so these work there too; og: is kept as a fallback.

// The post's own TEXT — the headline shown to the reader, used as the capture
// title (e.g. "JUST IN: GTA 6 pre-orders officially begin on June 25."). Lives in
// <div class="tgme_widget_message_text …">…</div>. Inline tags (<b>/<i>/<a>) are
// dropped and <br> becomes a newline so a clean first line can be taken for the
// title; the full text is kept for the description. decodeHtmlEntities runs later
// at the sendVariants emit choke point.
function extractMessageText(html) {
    const m = html.match(/tgme_widget_message_text[^>]*>([\s\S]*?)<\/div>/i);
    if (!m) return null;
    const txt = m[1]
        .replace(/<br\s*\/?>/gi, "\n")
        .replace(/<[^>]*>/g, "")
        .replace(/[ \t]+/g, " ")
        .replace(/\n{2,}/g, "\n")
        .trim();
    return txt || null;
}

// The channel/author name from
// <a class="tgme_widget_message_owner_name" …><span>Watcher Guru</span></a>.
function extractAuthor(html) {
    const m = html.match(/tgme_widget_message_owner_name[^>]*>([\s\S]*?)<\/a>/i);
    if (!m) return null;
    const t = m[1].replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
    return t || null;
}

// Per-clip poster URLs from the widget's video-thumb background-image, document-
// ordered to line up with collectVideoSrcs (used as the capture thumbnail when
// the embed iframe has no og:image).
function collectThumbs(html) {
    const out = [];
    const re = /tgme_widget_message_video_thumb[^>]*background-image:url\((['"]?)([^'")]+)\1\)/gi;
    let m;
    while ((m = re.exec(html)) !== null) out.push(m[2]);
    return out;
}

// Compose the capture title as "<Channel> — <post headline>", e.g.
// "Watcher Guru — JUST IN: GTA 6 pre-orders officially begin on June 25.". The
// headline is the post text's first non-empty line, capped so a long post
// doesn't become an unwieldy filename. Falls back to just the channel for a
// text-less video post, or just the headline when the channel is unknown.
function titleFromPost(text, author) {
    let line = null;
    if (text) {
        const fl = text.split("\n").map(s => s.trim()).find(s => s.length > 0);
        line = fl || text.trim();
        if (line.length > 120) line = line.slice(0, 119).trimEnd() + "…";
    }
    if (author && line) return `${author} — ${line}`;
    return line || author || null;
}

// Shared per-post emit: extract every <video src=…mp4> from an HTML chunk (a
// single post document/iframe, OR one message block sliced out of a /s/ feed)
// and emit each as a variant with the post's title/thumbnail/duration. Used by
// BOTH the single-post listener and the feed listener so the metadata logic
// lives in one place. `allowOgVideo` enables the poster-only og:video fallback
// (single-post pages only; feed blocks always carry a real <video src>).
// `forceMulti` forces a per-clip dedupKey (the feed holds many clips under one
// page origin). Returns the number of videos emitted.
function emitVideosFromHtml(details, html, origin, { allowOgVideo, forceMulti }) {
    let srcs = collectVideoSrcs(html);
    let widthFromMeta = 0, heightFromMeta = 0;
    if (srcs.length === 0 && allowOgVideo) {
        // Open Graph spec: og:video (legacy) / og:video:url / og:video:secure_url.
        const ogVideo = metaContent(html, "og:video")
            || metaContent(html, "og:video:secure_url")
            || metaContent(html, "og:video:url");
        if (ogVideo && /\.mp4(?:[?#]|$)/i.test(ogVideo)) {
            srcs = [ogVideo];
            widthFromMeta = parseInt(metaContent(html, "og:video:width"), 10) || 0;
            heightFromMeta = parseInt(metaContent(html, "og:video:height"), 10) || 0;
        }
    }
    if (srcs.length === 0) return 0;

    // Title = "<Channel> — <post text>". The embed iframe / feed block carry no
    // og: tags, so read the widget markup (message_text / owner_name /
    // video_thumb) with og: as a fallback for the landing/single-/s/ forms.
    const postText = extractMessageText(html) || metaContent(html, "og:description");
    const author = extractAuthor(html) || metaContent(html, "og:title");
    const name = titleFromPost(postText, author);
    const description = postText || author;
    const ogImg = metaContent(html, "og:image");
    const thumbs = collectThumbs(html);

    // Durations pair with videos BY DOCUMENT ORDER; trust the per-index pairing
    // ONLY when the counts match (a mixed/partial post would mis-stamp clip N
    // with clip N+1's length). Otherwise pass 0 and let the ffmpeg probe read it.
    const durations = collectDurations(html);
    const durationsAligned = durations.length === srcs.length;

    const multi = forceMulti || srcs.length > 1;
    for (let i = 0; i < srcs.length; i++) {
        const url = srcs[i];
        sendVariants(details, {
            variants: [{ url, width: widthFromMeta, height: heightFromMeta }],
            origin,
            name,
            description,
            // Per-clip thumbnail: og:image (landing/single-/s/) else the widget's
            // own video-thumb, paired by index, falling back to the first.
            img: ogImg || thumbs[i] || thumbs[0] || undefined,
            // parseClock returns SECONDS; the native pipeline expects the message
            // `duration` in MILLISECONDS (JsonHelper → GeckoInspectTask does
            // ms→µs). Passing seconds made a 0:32 clip show as ~0.03s.
            duration: durationsAligned && durations[i] ? durations[i] * 1000 : 0,
            // Single video → origin dedup (collapses refreshes even if the CDN
            // token rotates). Album / feed → per-clip dedupKey so distinct clips
            // don't collapse to one entity.
            dedupKey: multi ? url : undefined,
        });
    }
    return srcs.length;
}

function listenerTelegramPage(details) {
    // Read BOTH the top document AND the embed widget iframe. Opening a post
    // URL directly (t.me/<channel>/<id>) serves a LANDING page as the main_frame
    // that carries only og:title + og:image (the poster) — NO <video>/og:video,
    // so the video is invisible there (confirmed from a HAR: WatcherGuru/14028's
    // main_frame had zero <video>). The actual <video src=…mp4> lives in the
    // widget the landing page embeds, t.me/<channel>/<id>?embed=1&mode=tme, which
    // loads as a SUB_FRAME. Gating on main_frame alone (the old behaviour) missed
    // every such post. The /s/<channel>/<id> web-preview form does carry the
    // video in its main_frame, so accepting both frame types covers both layouts;
    // the landing main_frame simply finds nothing and returns (no duplicate, and
    // the emitted .mp4 is parser-block-listed against the generic catcher anyway).
    if (details.type !== "main_frame" && details.type !== "sub_frame") return;
    const post = (details.url || "").match(TELEGRAM_POST_RE);
    if (!post) return;
    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);

    const channel = post[1];
    const id = post[2];
    const origin = `https://t.me/${channel}/${id}`;

    readFilteredBody(details, "TELEGRAM", "doc filter", (html, bytes) => {
        const n = emitVideosFromHtml(details, html, origin, { allowOgVideo: true, forceMulti: false });
        log("TELEGRAM", `doc filter: ${bytes} bytes, ${n} video(s)`, { origin });
    });
}

// ----------------------------------------------------------------------------
// Channel FEED (t.me/s/<channel>) — the web preview of a whole channel.
// ----------------------------------------------------------------------------
// Browsing a channel feed is a distinct surface from a single post: the URL has
// NO post id (t.me/s/WatcherGuru), so TELEGRAM_POST_RE doesn't match it and the
// single-post listener bails. The feed renders MANY posts, and as the user
// scrolls it loads older batches via pagination XHRs
// (POST t.me/s/<channel>?before=<id>) whose body is a JSON-ENCODED HTML STRING
// (escaped slashes). Each post is a `tgme_widget_message_wrap` carrying
// data-post="<channel>/<id>", and a post's <video src=…mp4> is present right in
// that markup (confirmed from a HAR: the ?before= batches held the videos the
// user couldn't capture). So: match the feed URL, read BOTH the main_frame and
// the xmlhttprequest pagination, JSON-unwrap when needed, split into per-message
// blocks, and run the SAME per-post emit on each block (origin = the block's own
// data-post id, so each clip is its own entity). The emitted .mp4 stays
// parser-block-listed, so the generic catcher can't double-capture on play.
const TELEGRAM_FEED_RE = /^https?:\/\/t\.me\/s\/([A-Za-z0-9_]+)(?:[?#]|$)/;

// The pagination body is a JSON string (or {html:…}/[html,…]); the main_frame is
// plain HTML. Unwrap to the HTML, leaving plain HTML untouched.
function unwrapFeedBody(raw) {
    const t = raw.trimStart();
    if (t[0] === '"' || t[0] === '[' || t[0] === '{') {
        const parsed = tryParseJson(t);
        if (typeof parsed === "string") return parsed;
        if (parsed && typeof parsed === "object") {
            if (typeof parsed.html === "string") return parsed.html;
            if (Array.isArray(parsed) && typeof parsed[0] === "string") return parsed[0];
        }
    }
    return raw;
}

// Slice a feed document into per-message blocks at each tgme_widget_message_wrap
// boundary so the per-post helpers run scoped to one post. Falls back to the
// whole document as one block when the wrapper class isn't present.
function splitMessages(html) {
    const re = /tgme_widget_message_wrap/g;
    const idx = [];
    let m;
    while ((m = re.exec(html)) !== null) idx.push(m.index);
    if (idx.length === 0) return [html];
    const blocks = [];
    for (let i = 0; i < idx.length; i++) {
        blocks.push(html.slice(idx[i], i + 1 < idx.length ? idx[i + 1] : html.length));
    }
    return blocks;
}

function listenerTelegramFeed(details) {
    // The initial feed is a main_frame; older batches arrive as XHRs.
    if (details.type !== "main_frame" && details.type !== "xmlhttprequest") return;
    const m = (details.url || "").match(TELEGRAM_FEED_RE);
    if (!m) return;
    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);
    const channel = m[1];

    readFilteredBody(details, "TELEGRAM", "feed filter", (raw, bytes) => {
        const html = unwrapFeedBody(raw);
        const blocks = splitMessages(html);
        let total = 0;
        for (const block of blocks) {
            // Per-post origin from data-post="<channel>/<id>"; fall back to the
            // feed origin if a block somehow lacks it (keeps clips distinct via
            // the per-clip dedupKey anyway).
            const idm = block.match(/data-post="[A-Za-z0-9_]+\/(\d+)"/);
            const origin = idm ? `https://t.me/${channel}/${idm[1]}` : `https://t.me/s/${channel}`;
            total += emitVideosFromHtml(details, block, origin, { allowOgVideo: false, forceMulti: true });
        }
        log("TELEGRAM", `feed filter: ${bytes} bytes, ${blocks.length} post(s), ${total} video(s)`, { channel });
    });
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTelegramPage,
    // sub_frame too: the post's <video> lives in the embedded ?embed=1 widget
    // iframe, not the landing main_frame (see listenerTelegramPage's note).
    { urls: ["*://t.me/*"], types: ["main_frame", "sub_frame"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerTelegramFeed,
    // The feed URL is /s/<channel> (no id, so it never reaches the post listener
    // above); pagination is an xmlhttprequest to the same path with ?before=.
    { urls: ["*://t.me/s/*"], types: ["main_frame", "xmlhttprequest"] },
    ["blocking"]
);

// Pure helpers exported for the smoke test's HAR-replay assertion (it runs the
// REAL extractors against a tgme-widget fixture — no copy-pasted simulation).
export {
    TELEGRAM_POST_RE, TELEGRAM_FEED_RE, metaContent, collectVideoSrcs, collectDurations, parseClock,
    extractMessageText, extractAuthor, collectThumbs, titleFromPost,
    unwrapFeedBody, splitMessages,
};

// ============================================================================
