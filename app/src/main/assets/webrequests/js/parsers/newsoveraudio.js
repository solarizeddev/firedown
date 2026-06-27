// News Over Audio (NOA) parser.
import { log, tryParseJson, sendNative, collectFilteredResponse, resolveTabId } from './common.js';

// ============================================================================
// News Over Audio  —  https://newsoveraudio.com
// ============================================================================
//
// NOA is a syndicated "listen to this article" widget embedded across many
// publishers (IEEE Spectrum here; the same embed appears on others). The audio
// is a real, full-length, AI-narrated reading of the article — a signed,
// downloadable .mp3 on NOA's CloudFront CDN — NOT a preview and NOT DRM.
//
// The embed is a CROSS-ORIGIN iframe (embed-player.newsoveraudio.com) whose
// player fetches its metadata from:
//
//   GET https://api.newsoveraudio.com/v1/player/article?code=<articleUrl>&...
//
// returning (application/json — which the generic catcher rejects):
//
//   { "message": "Article found",
//     "data": { "article": {
//        "name": "What it Means to Be a Mathematician When AI Does the Math",
//        "audio": "https://audios.newsoveraudio.com/articles/medium/<id>.mp3?Expires=…&Signature=…",
//        "audioLength": 1009.881,                 // SECONDS
//        "image": null,
//        "articleOriginUrl": "https://spectrum.ieee.org/ai-in-mathematics?draft=1",
//        "publisher": { "name": "IEEE Spectrum",
//                       "smallImage": "…", "largeImage": "…" }
//   } } }
//
// Why a parser (vs. the generic title path): the audio plays from the
// cross-origin iframe, so its <audio> binding is invisible to the top-frame
// metadata responder (audioRole stays 'unknown' → standalone-audio title
// suppressed), AND the title/url live ONLY in the application/json body the
// catcher won't read. So neither the wire nor the DOM nor the page-state bridge
// can title it. We filterResponseData on the player JSON (same pattern as
// Apple Podcasts / Instagram) and emit one titled audio entry per article. The
// emitted .mp3 host is block-listed (parser-blocklist.js `newsoveraudio`) so the
// generic catcher doesn't also grab a bare copy when the player fetches it.

const processedNoaUrls = new Set();

function listenerNoaArticle(details) {
    collectFilteredResponse(details).then(async (text) => {
        const json = tryParseJson(text);
        const article = json?.data?.article;
        if (!article?.audio) {
            log("NOA", `player response has no article audio`, { url: details.url.slice(0, 120) });
            return;
        }

        // Dedup on the article id (or the audio URL when no id) — the embed may
        // re-fetch the player JSON, and a re-emit of the same URL is harmless
        // (the repository dedups by URL) but noisy in the logs.
        const dedupKey = "noa:" + (article.id ?? article.audio);
        if (processedNoaUrls.has(dedupKey)) return;
        processedNoaUrls.add(dedupKey);
        setTimeout(() => processedNoaUrls.delete(dedupKey), 30000);

        const tabId = await resolveTabId(details);
        // audioLength is in SECONDS — the native side wants milliseconds.
        const durationMs = typeof article.audioLength === "number" && article.audioLength > 0
            ? Math.round(article.audioLength * 1000)
            : undefined;
        const publisher = article.publisher || {};

        const message = {
            url: article.audio,
            type: "media",
            // The publisher article page is the natural origin (groups the audio
            // with the page the user is on); fall back to the embed document.
            origin: article.articleOriginUrl || details.documentUrl || details.originUrl || details.url,
            tabId,
            request: details.requestId,
            name: article.name || undefined,
            // The publisher name is the "series" equivalent (IEEE Spectrum, …).
            description: publisher.name || undefined,
            // The article rarely carries its own image; the publisher icon is the
            // reliable thumbnail.
            img: article.image || publisher.largeImage || publisher.smallImage || undefined,
            duration: durationMs
        };
        for (const k of Object.keys(message)) {
            if (message[k] === undefined) delete message[k];
        }

        // Duration came from the player JSON, so skip the metadatareader probe —
        // the URL is a recognizable .mp3 so the native side confirms it's audio
        // and honours skipProbe (else it still probes — same gate as Apple
        // Podcasts).
        if (typeof message.duration === "number" && message.duration > 0) message.skipProbe = true;

        log("NOA", `Found article`, {
            name: message.name,
            publisher: publisher.name,
            url: message.url?.slice(0, 100),
            tabId
        });
        sendNative(message);
    }).catch((e) => {
        log("NOA", `filter error`, e.message);
    });

    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerNoaArticle,
    {
        urls: ["*://api.newsoveraudio.com/v1/player/article*"],
        types: ["xmlhttprequest"]
    },
    ["blocking"]
);
