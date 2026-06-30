// telegram-web.js — Telegram WEB APP (web.telegram.org K & A) restricted-media
// download. This is a DIFFERENT surface from the public t.me parser
// (js/parsers/telegram.js): t.me posts expose a real, re-fetchable CDN .mp4 that
// fits the normal capture→native-redownload pipeline; the LOGGED-IN web app does
// NOT.
//
// Why the normal pipeline can't touch web.telegram.org media
// ----------------------------------------------------------------------------
// In the web app a media element's URL is a ServiceWorker-virtual path
// (…/k/stream/<json> or …/a/stream/<json>). The SW serves it from MTProto chunks
// the page DECRYPTS IN JAVASCRIPT — there is no plain, native-reachable URL and
// no small key we could hand the native downloader (unlike Mega, where the page
// hands native a key and native fetches+decrypts a real CDN URL). A native
// OkHttp re-fetch of a /stream/ URL doesn't route through the page's SW and
// can't speak MTProto, so it 401/404s. The bytes only exist inside the page.
//
// How we get the bytes to disk anyway (no Java changes)
// ----------------------------------------------------------------------------
// We do exactly what a userscript would (the technique is from the GPL'd
// Neet-Nestor/Telegram-Media-Downloader, reimplemented here): fetch the /stream/
// URL with HTTP Range requests IN THE PAGE WORLD, assemble the chunks into a
// Blob, and trigger an `<a download href="blob:…">`. Firedown's existing
// download interception catches the blob navigation
// (GeckoComponents.onExternalResponse → BrowserFragment.onDownload →
// GeckoStreamStrategy streams response.body to disk), so the blob is saved
// through the normal download dialog. No new native plumbing.
//
// Why PAGE WORLD, not the content-script's isolated world
// ----------------------------------------------------------------------------
// Two reasons, both load-bearing:
//   1. fetch('/stream/…') must be intercepted by the page's ServiceWorker and
//      carry the logged-in session. A SW intercepts fetches from ITS CLIENT (the
//      controlled page) — a content-script-context fetch is not that client, so
//      it would bypass the SW and fail. The page-world fetch is the SW's client.
//   2. URL.createObjectURL(blob)/<a download> must produce a blob URL the
//      docshell can navigate so it reaches onExternalResponse; a blob minted in
//      the content-script partition may not resolve for the page navigation.
// Both are satisfied by running the engine in the page world. We do that the
// SAME confirmed way youtube/content.js mints its PoToken: `window.wrappedJSObject
// .eval(...)` (privileged content-script eval is NOT subject to the page CSP —
// that's why this works where a <script> inject / web-accessible-resource would
// hit Telegram's CSP). The engine is authored as a normal function and injected
// via `.toString()` so it stays readable/lint-able; it touches only standard
// browser globals (window/document/fetch/URL/Blob), never extension APIs.
//
// Dedup with the generic catcher: the /stream/ URL is block-listed in
// parser-blocklist.js (`telegram-web`) — NOT to pair with a pipeline emit (there
// is none; we download directly), but because the catcher would otherwise emit a
// HARMFUL capture: a non-re-fetchable /stream/ URL that fails on every download
// attempt. Same "block a harmful capture" rationale as Mega/Bilibili in that
// file, not the ordinary cardinal-rule dedup.
//
// Verify on device (cannot be checked off-device): (a) the page-world fetch
// reaches the SW and returns 206 chunks; (b) the blob <a download> surfaces the
// Firedown download dialog and saves a playable file; (c) the DOM selectors
// below still match current Telegram Web (they track the userscript and Telegram
// updates its markup — adjust K_* / A_* if the button stops appearing).
// ============================================================================

(function () {
    "use strict";

    // Top frame only — the media viewer is a top-level overlay; running the
    // engine in cross-origin sub-iframes would be wasted work.
    if (window.top !== window) return;

    // Debug flag from BuildConfig.DEBUG via the native bridge (CLAUDE.md logging
    // discipline). We inject the engine in BOTH the resolve and reject paths so a
    // failed/absent debug reply never stops the feature loading (it just logs
    // nothing). Guarded so it injects at most once.
    let injected = false;

    function inject(debug) {
        if (injected) return;
        injected = true;
        try {
            // Run the engine in the page world. The function is self-contained
            // and parameterised by the debug flag.
            const src = "(" + tgWebEngine.toString() + ")(" + (debug ? "true" : "false") + ");";
            window.wrappedJSObject.eval(src);
        } catch (e) {
            if (debug) console.log("[TG-WEB] page-engine inject failed:", e && e.message);
        }
    }

    browser.runtime.sendNativeMessage("browser", { kind: "get-debug-flag" })
        .then((r) => inject(r === true), () => inject(false));

    // ========================================================================
    // PAGE-WORLD ENGINE — runs via wrappedJSObject.eval, NOT in this isolated
    // world. Uses only standard browser globals. Keep it dependency-free.
    // ========================================================================
    function tgWebEngine(DEBUG) {
        "use strict";

        // Idempotent: a reload re-runs the content script, but one document keeps
        // one engine (the SPA navigates without reloading).
        if (window.__fdTgWebInit) return;
        window.__fdTgWebInit = true;

        const log = function () {
            if (!DEBUG) return;
            const a = ["[TG-WEB]"];
            for (let i = 0; i < arguments.length; i++) a.push(arguments[i]);
            console.log.apply(console, a);
        };
        log("engine init", location.href);

        // Telegram serves /stream/ media in chunks; one plain GET may be capped,
        // so we walk it with Range requests (the proven userscript approach).
        const CONTENT_RANGE_RE = /^bytes (\d+)-(\d+)\/(\d+)$/;
        const BTN_CLASS = "fd-tg-dl-btn";

        // --- selectors (track Telegram Web; mirror the userscript) -----------
        // Web K (web.telegram.org/k/, webk.telegram.org)
        const K_VIEWER = ".media-viewer-whole";
        const K_ASPECTER = ".media-viewer-movers .media-viewer-aspecter";
        const K_BUTTONS = ".media-viewer-topbar .media-viewer-buttons";
        // Web A / Z (web.telegram.org/a/, webz.telegram.org)
        const A_SLIDE = "#MediaViewer .MediaViewerSlide--active";
        const A_ACTIONS = "#MediaViewer .MediaViewerActions";

        function hashCode(s) {
            let h = 0;
            for (let i = 0; i < s.length; i++) {
                h = ((h << 5) - h) + s.charCodeAt(i);
                h |= 0;
            }
            return h;
        }
        function shortId(s) {
            return Math.abs(hashCode(String(s))).toString(36);
        }

        // The K /stream/ (and /download/) URL encodes a JSON describing the file:
        // {dcId, location, size, mimeType, fileName}. Pull the REAL fileName +
        // mimeType so the saved file keeps its original name/type ("IMG_7728.MP4")
        // instead of a hash. Returns null for a non-/stream/ URL (e.g. a blob:
        // image), where the caller falls back to a hash name.
        function streamMeta(url) {
            try {
                const marker = url.indexOf("/stream/") >= 0 ? "/stream/"
                    : (url.indexOf("/download/") >= 0 ? "/download/" : null);
                if (!marker) return null;
                const j = JSON.parse(decodeURIComponent(url.slice(url.indexOf(marker) + marker.length)));
                return { fileName: (j && j.fileName) || null, mimeType: (j && j.mimeType) || null, size: (j && j.size) || 0 };
            } catch (e) {
                return null;
            }
        }

        // Locate the open media viewer and the media it shows. Returns null when
        // no viewer is open or it holds nothing downloadable.
        //   { kind: "video"|"image", url, actionBar, fileName }
        function detect() {
            // --- Web K ---
            const kViewer = document.querySelector(K_VIEWER);
            if (kViewer && kViewer.offsetParent !== null) {
                const aspecter = kViewer.querySelector(K_ASPECTER);
                const buttons = kViewer.querySelector(K_BUTTONS);
                if (aspecter && buttons) {
                    const video = aspecter.querySelector("video");
                    const vurl = video && (video.currentSrc || video.src);
                    if (vurl) {
                        const meta = streamMeta(vurl);
                        return {
                            kind: "video", url: vurl, actionBar: buttons,
                            fileName: (meta && meta.fileName) || ("Telegram_" + shortId(vurl) + ".mp4"),
                            blobType: (meta && meta.mimeType) || "video/mp4",
                        };
                    }
                    const img = aspecter.querySelector("img.thumbnail");
                    const iurl = img && img.src;
                    if (iurl) {
                        const meta = streamMeta(iurl);
                        return {
                            kind: "image", url: iurl, actionBar: buttons,
                            fileName: (meta && meta.fileName) || ("Telegram_" + shortId(iurl) + ".jpeg"),
                            blobType: (meta && meta.mimeType) || "image/jpeg",
                        };
                    }
                }
            }
            // --- Web A / Z ---
            const aSlide = document.querySelector(A_SLIDE);
            const aActions = document.querySelector(A_ACTIONS);
            if (aSlide && aActions) {
                const videoPlayer = aSlide.querySelector(".MediaViewerContent > .VideoPlayer");
                const video = videoPlayer && videoPlayer.querySelector("video");
                const vurl = video && (video.currentSrc || video.src);
                if (vurl) {
                    const meta = streamMeta(vurl);
                    return {
                        kind: "video", url: vurl, actionBar: aActions,
                        fileName: (meta && meta.fileName) || ("Telegram_" + shortId(vurl) + ".mp4"),
                        blobType: (meta && meta.mimeType) || "video/mp4",
                    };
                }
                const img = aSlide.querySelector(".MediaViewerContent > div > img");
                const iurl = img && img.src;
                if (iurl) {
                    const meta = streamMeta(iurl);
                    return {
                        kind: "image", url: iurl, actionBar: aActions,
                        fileName: (meta && meta.fileName) || ("Telegram_" + shortId(iurl) + ".jpeg"),
                        blobType: (meta && meta.mimeType) || "image/jpeg",
                    };
                }
            }
            return null;
        }

        // Save assembled bytes via a blob <a download> — Firedown's download
        // interception (onExternalResponse) catches the blob navigation and
        // streams it to disk through the normal download dialog.
        function saveBlob(blob, fileName) {
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = fileName;
            a.style.display = "none";
            document.body.appendChild(a);
            a.click();
            a.remove();
            // Revoke late — the download navigation must have consumed the URL.
            setTimeout(function () { try { URL.revokeObjectURL(url); } catch (e) {} }, 60000);
        }

        // Range-walk a /stream/ (or any chunked) URL into one Blob, reporting
        // progress. blobType is the assembled container mime ("video/mp4" etc.).
        function downloadChunked(url, fileName, blobType, onProgress, onDone, onError) {
            let nextOffset = 0;
            let totalSize = 0;
            const parts = [];

            function fetchPart() {
                fetch(url, { method: "GET", headers: { Range: "bytes=" + nextOffset + "-" } })
                    .then(function (res) {
                        if (res.status !== 200 && res.status !== 206) {
                            throw new Error("HTTP " + res.status);
                        }
                        const cr = res.headers.get("Content-Range");
                        if (cr) {
                            const m = cr.match(CONTENT_RANGE_RE);
                            if (!m) throw new Error("bad Content-Range: " + cr);
                            const start = parseInt(m[1], 10);
                            const end = parseInt(m[2], 10);
                            const total = parseInt(m[3], 10);
                            // A gap means the server didn't honour our offset — bail
                            // rather than silently assemble a corrupt file.
                            if (start !== nextOffset) throw new Error("range gap");
                            nextOffset = end + 1;
                            totalSize = total;
                        } else {
                            // No Content-Range: a 200 with the whole body in one shot.
                            nextOffset = -1;
                        }
                        return res.blob();
                    })
                    .then(function (chunk) {
                        parts.push(chunk);
                        if (totalSize > 0 && onProgress) {
                            const got = nextOffset === -1 ? totalSize : Math.min(nextOffset, totalSize);
                            onProgress(Math.min(100, Math.round((got / totalSize) * 100)));
                        }
                        if (nextOffset !== -1 && nextOffset < totalSize) {
                            fetchPart();
                            return;
                        }
                        saveBlob(new Blob(parts, { type: blobType }), fileName);
                        if (onDone) onDone();
                    })
                    .catch(function (e) {
                        log("chunked download failed:", e && e.message);
                        if (onError) onError(e);
                    });
            }
            fetchPart();
        }

        // An image URL is small and may be a blob: (already-decrypted bytes) or a
        // /stream/ URL — a single page-world fetch handles both: fetch → blob →
        // save (no Range walk needed).
        function downloadImage(url, fileName, onDone, onError) {
            fetch(url, { method: "GET" })
                .then(function (res) {
                    if (!res.ok) throw new Error("HTTP " + res.status);
                    return res.blob();
                })
                .then(function (blob) {
                    saveBlob(blob, fileName);
                    if (onDone) onDone();
                })
                .catch(function (e) {
                    log("image download failed:", e && e.message);
                    if (onError) onError(e);
                });
        }

        // --- tiny progress overlay (bottom-right), one row per download ------
        let progressHost = null;
        function progressRow(label) {
            if (!progressHost) {
                progressHost = document.createElement("div");
                progressHost.setAttribute("style",
                    "position:fixed;right:12px;bottom:12px;z-index:2147483647;" +
                    "display:flex;flex-direction:column;gap:6px;pointer-events:none;font:13px/1.3 sans-serif;");
                document.body.appendChild(progressHost);
            }
            const row = document.createElement("div");
            row.setAttribute("style",
                "background:#212d3b;color:#fff;border-radius:8px;padding:8px 12px;" +
                "box-shadow:0 2px 8px rgba(0,0,0,.4);min-width:180px;opacity:.97;");
            const text = document.createElement("div");
            text.textContent = label;
            const bar = document.createElement("div");
            bar.setAttribute("style", "height:4px;border-radius:2px;background:#0f1620;margin-top:6px;overflow:hidden;");
            const fill = document.createElement("div");
            fill.setAttribute("style", "height:100%;width:0%;background:#3390ec;transition:width .2s;");
            bar.appendChild(fill);
            row.appendChild(text);
            row.appendChild(bar);
            progressHost.appendChild(row);
            return {
                set: function (pct) { fill.style.width = pct + "%"; },
                done: function () {
                    text.textContent = "Saved — see Downloads";
                    fill.style.width = "100%";
                    setTimeout(function () { try { row.remove(); } catch (e) {} }, 4000);
                },
                fail: function () {
                    text.textContent = "Download failed";
                    fill.style.background = "#e53935";
                    setTimeout(function () { try { row.remove(); } catch (e) {} }, 6000);
                }
            };
        }

        function startDownload(media) {
            const label = media.kind === "video" ? "Downloading video…"
                : media.kind === "image" ? "Downloading image…" : "Downloading…";
            const ui = progressRow(label);
            if (media.kind === "image") {
                downloadImage(media.url, media.fileName, ui.done, ui.fail);
            } else {
                downloadChunked(media.url, media.fileName, media.blobType || "video/mp4", ui.set, ui.done, ui.fail);
            }
        }

        // --- download button injection ---------------------------------------
        function makeButton() {
            const btn = document.createElement("button");
            btn.className = BTN_CLASS;
            btn.type = "button";
            btn.title = "Download (Firedown)";
            btn.setAttribute("aria-label", "Download");
            // Neutral, theme-agnostic styling that reads on Telegram's dark
            // viewer chrome without depending on its CSS classes.
            btn.setAttribute("style",
                "display:inline-flex;align-items:center;justify-content:center;" +
                "width:40px;height:40px;margin:0 2px;border:0;background:transparent;" +
                "color:currentColor;cursor:pointer;border-radius:50%;font-size:20px;line-height:1;");
            btn.textContent = "⤓"; // ⤓ downwards arrow to bar
            btn.addEventListener("click", function (ev) {
                ev.preventDefault();
                ev.stopPropagation();
                // Re-detect at click time — the viewer may have swiped to a new
                // item since the button was injected.
                const media = detect();
                if (!media) { log("click: nothing to download"); return; }
                log("download click", media.kind, media.url.slice(0, 80));
                startDownload(media);
            }, true);
            return btn;
        }

        function ensureButton() {
            const media = detect();
            if (!media) return;
            if (media.actionBar.querySelector("." + BTN_CLASS)) return; // already there
            try {
                media.actionBar.prepend(makeButton());
                log("button injected", media.kind);
            } catch (e) {
                log("button inject failed:", e && e.message);
            }
        }

        // The viewer opens/closes/swipes by DOM mutation; observe and (re)inject.
        // Debounced so a burst of mutations does one check.
        let pending = false;
        const observer = new MutationObserver(function () {
            if (pending) return;
            pending = true;
            setTimeout(function () { pending = false; ensureButton(); }, 150);
        });
        observer.observe(document.body, { childList: true, subtree: true });
        // Initial pass in case a viewer is already open at inject time.
        ensureButton();
    }
})();
