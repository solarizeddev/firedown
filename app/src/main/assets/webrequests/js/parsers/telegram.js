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
import { log, sendVariants, cacheTabUrl, readFilteredBody } from './common.js';

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

// Pick a concise title from the post text: its first non-empty line, capped so a
// long post doesn't become an unwieldy filename. Falls back to the author when
// the post has no text (a bare video post).
function titleFromPost(text, author) {
    let t = text || author || null;
    if (!t) return null;
    const firstLine = t.split("\n").map(s => s.trim()).find(s => s.length > 0);
    t = firstLine || t;
    if (t.length > 140) t = t.slice(0, 139).trimEnd() + "…";
    return t;
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
        // Title = the POST'S OWN TEXT (the headline the reader sees), not the
        // channel name. The embed iframe carries no og: tags, so read the widget
        // markup (message_text / owner_name / video_thumb) with og: as a fallback
        // for the /s/ and landing forms. name = first line of the post text
        // (capped); description = the full post text; author/og:title is the
        // fallback for a bare video post with no text.
        const postText = extractMessageText(html) || metaContent(html, "og:description");
        const author = extractAuthor(html) || metaContent(html, "og:title");
        const name = titleFromPost(postText, author);
        const description = postText || author;
        const ogImg = metaContent(html, "og:image");
        const thumbs = collectThumbs(html);

        // <video src> first (covers album posts); fall back to og:video for a
        // poster-only page that hasn't materialised the <video> element. Open
        // Graph spec defines og:video (legacy plain URL), og:video:url, and
        // og:video:secure_url (https) — Telegram has used og:video historically,
        // but check the spec'd siblings too so a markup change to the canonical
        // og:video:url/secure_url form still captures the poster-only case.
        let srcs = collectVideoSrcs(html);
        let widthFromMeta = 0, heightFromMeta = 0;
        if (srcs.length === 0) {
            const ogVideo = metaContent(html, "og:video")
                || metaContent(html, "og:video:secure_url")
                || metaContent(html, "og:video:url");
            if (ogVideo && /\.mp4(?:[?#]|$)/i.test(ogVideo)) {
                srcs = [ogVideo];
                widthFromMeta = parseInt(metaContent(html, "og:video:width"), 10) || 0;
                heightFromMeta = parseInt(metaContent(html, "og:video:height"), 10) || 0;
            }
        }

        const durations = collectDurations(html);
        // Durations are paired with videos BY DOCUMENT ORDER (index), which is
        // only sound when every video contributed exactly one duration <time>.
        // A mixed/partial post breaks that 1:1 alignment — e.g. a photo+video
        // album (a photo has no message_video_duration) or a clip that hasn't
        // rendered its <time> yet — and a misaligned index would stamp clip N
        // with clip N+1's length. So trust the per-index pairing ONLY when the
        // counts match; otherwise pass 0 and let sendVariants' ffmpeg probe read
        // the real duration (correctness over skipping the probe).
        const durationsAligned = durations.length === srcs.length;
        log("TELEGRAM", `doc filter: ${bytes} bytes, ${srcs.length} video(s)`, { origin });
        if (srcs.length === 0) return;

        const multi = srcs.length > 1;
        for (let i = 0; i < srcs.length; i++) {
            const url = srcs[i];
            // Single video → origin dedup (collapses page refreshes even if the
            // CDN token rotates). Album → per-clip dedupKey so the clips don't
            // collapse to one entity (the bridge's multi-clip pattern).
            sendVariants(details, {
                variants: [{ url, width: widthFromMeta, height: heightFromMeta }],
                origin,
                name,
                description,
                // Per-clip thumbnail: og:image (landing/​/s/) else the widget's
                // own video-thumb, paired by index, falling back to the first.
                img: ogImg || thumbs[i] || thumbs[0] || undefined,
                // parseClock returns SECONDS; the native pipeline expects the
                // message `duration` in MILLISECONDS (JsonHelper → GeckoInspectTask
                // does ms→µs). Passing seconds made a 0:32 clip show as ~0.03s
                // ("00:00:00:03"). Convert here, keeping collectDurations in the
                // natural M:SS→seconds unit.
                duration: durationsAligned && durations[i] ? durations[i] * 1000 : 0,
                dedupKey: multi ? url : undefined,
            });
        }
    });
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTelegramPage,
    // sub_frame too: the post's <video> lives in the embedded ?embed=1 widget
    // iframe, not the landing main_frame (see listenerTelegramPage's note).
    { urls: ["*://t.me/*"], types: ["main_frame", "sub_frame"] },
    ["blocking"]
);

// Pure helpers exported for the smoke test's HAR-replay assertion (it runs the
// REAL extractors against a tgme-widget fixture — no copy-pasted simulation).
export {
    TELEGRAM_POST_RE, metaContent, collectVideoSrcs, collectDurations, parseClock,
    extractMessageText, extractAuthor, collectThumbs, titleFromPost,
};

// ============================================================================
