import { log, registerPlaceholderHandler, queuePlaceholderCapture, tabOfDetails } from './common.js';

// ============================================================================
// YouTube embed facades — a click-to-load placeholder whose capture lives in
// the SEPARATE youtube@ extension
// ============================================================================
// A page often shows a YouTube embed as a thumbnail with a play button and
// creates the player iframe only on the tap: a web component that keeps the
// iframe's HTML in an attribute, a <lite-youtube videoid>, a lazy-loaded
// iframe. Until the tap no youtube.com document exists, so the youtube@
// extension — whose content script and webRequest listeners run on YouTube
// pages and frames — sees nothing, and the generic catcher cannot help
// (googlevideo is parser-owned). The content script reports the player URL
// such a facade DECLARES (common.js `player-placeholders`); this handler
// claims the YouTube ones and hands the video over to youtube@, which can
// capture from a video id and a tab alone: its processVideo fetches the
// watch page itself, the way its embed-frame path already runs it.
//
// The hand-over is a cross-extension message (runtime.sendMessage with the
// extension id; youtube@ listens on runtime.onMessageExternal and checks the
// sender). youtube@ answers true once it emitted, or when another path in
// that tab already owns the video (its own 60 s per-tab cache), false when it
// found no streams, so a reload tries again (the claim is released). A reply
// that never comes is bounded: past the timeout the capture is youtube@'s to
// finish and the claim is kept. Claims, the per-page budget, the one-per-tab
// queue and the page check are queuePlaceholderCapture's. Known residual:
// youtube@ emits under the tab's page at arrival, and its watch-page fetch
// takes seconds; a tab that moves meanwhile files the capture under the next
// page (a site handler re-checks before its own emit; this one cannot reach
// into another extension's).

const YOUTUBE_EXTENSION_ID = "youtube@solarized.dev";
const FACADE_REPLY_TIMEOUT_MS = 45_000;
const YOUTUBE_ID_RE = /^[A-Za-z0-9_-]{11}$/;

/** The video id a YouTube player / embed / short URL names, or null (a playlist embed names none). */
function youtubeIdOfPlayerUrl(url) {
    let u;
    try { u = new URL(url); } catch (_) { return null; }
    const host = u.hostname.toLowerCase().replace(/^(?:www|m|music)\./, "");
    let id = null;
    if (host === "youtu.be") {
        id = u.pathname.split("/")[1] || null;
    } else if (host === "youtube.com" || host === "youtube-nocookie.com") {
        const m = u.pathname.match(/^\/(?:embed|shorts|live|v|e)\/([^/?#]+)/);
        if (m) {
            id = m[1];
        } else if (u.pathname === "/watch") {
            id = u.searchParams.get("v");
        }
    }
    // "videoseries" is the playlist embed's path and, at eleven letters, the
    // one non-id that passes the id shape.
    return id && id !== "videoseries" && YOUTUBE_ID_RE.test(id) ? id : null;
}

function handleYoutubeFacade(url, details) {
    const videoId = youtubeIdOfPlayerUrl(url);
    if (!videoId) return false;
    queuePlaceholderCapture(details, `youtube:${videoId}`,
        (stillOnPage) => captureYoutubeFacade(details, url, videoId, stillOnPage));
    return true;
}

/** Resolves true once youtube@ owns the video in this tab, false to retry on a later report. */
async function captureYoutubeFacade(details, url, videoId, stillOnPage) {
    if (!(await stillOnPage())) return false;
    const message = {
        kind: "youtube-facade",
        videoId,
        tabId: tabOfDetails(details),
        pageUrl: String(details.tabUrl || ""),
        embedUrl: url,
    };
    log("YOUTUBE-FACADE", `handing over`, { videoId, tab: message.tabId });
    let reply;
    try {
        reply = await Promise.race([
            browser.runtime.sendMessage(YOUTUBE_EXTENSION_ID, message),
            new Promise((resolve) => setTimeout(() => resolve("timeout"), FACADE_REPLY_TIMEOUT_MS)),
        ]);
    } catch (e) {
        log("YOUTUBE-FACADE", `youtube@ unreachable`, { videoId, error: e?.message });
        return false;
    }
    if (reply === "timeout") {
        log("YOUTUBE-FACADE", `no reply yet, youtube@ keeps it`, { videoId });
        return true;
    }
    log("YOUTUBE-FACADE", `youtube@ replied`, { videoId, reply });
    return reply === true;
}

registerPlaceholderHandler(handleYoutubeFacade);

export { youtubeIdOfPlayerUrl, handleYoutubeFacade };
