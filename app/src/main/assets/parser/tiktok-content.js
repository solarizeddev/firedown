// Bridge between TikTok's page-world fetch/XHR and the parser
// extension's background.
//
// Why a page-world hook and not webRequest.filterResponseData?
//   * filterResponseData perturbs the response stream enough that
//     TikTok's React app shows a "something went wrong" overlay,
//     even with byte-exact pass-through.
//   * Refetching the api URL from the extension trips TikTok's
//     single-use msToken / X-Bogus signature → stripped response.
//   * The ServiceWorker on www.tiktok.com intercepts /related/item_list/
//     and filterResponseData can't tap SW-served responses.
//
// Observing the page's OWN fetch/XHR at the JS layer dodges all three:
// we read whatever the page already received, without touching the
// network stack.
//
// The injected script is loaded as a moz-extension:// URL rather than
// inline, because TikTok's CSP (`script-src 'self' ...`) blocks
// inline scripts even when injected by an extension content script.
// moz-extension:// resources are exempt from page CSP because they're
// a different origin.
//
// Dependency on Gecko Fingerprinting Protection. TikTok's anti-abuse
// stack uses a device/session fingerprint to drive two suppressions
// we directly care about: (1) the Take-A-Break modal that gates the
// profile view, and (2) per-session throttling that withholds
// /api/post/item_list/ on subsequent loads. Empirically — verified
// from side-by-side logs — when FPP is enabled the fingerprint never
// stabilises, neither suppression engages, and /api/post/ fires
// reliably on every cold load (the Take-A-Break overlay may still
// render visually but TikTok no longer holds the XHR back behind it).
// With FPP off, the same loads stochastically produce no XHR and
// require manual refreshes. Several elaborate workarounds tried here
// previously (auto-reload, multi-checkpoint scans, scroll-trigger
// nudges) were all chasing symptoms of that throttle. Don't add them
// back; if reliability regresses, check FPP state before patching.
(() => {
    'use strict';

    console.info('[TT] content script loaded ' + location.href);

    let DEBUG = false;
    const log = (...args) => { if (DEBUG) console.log(...args); };
    browser.runtime.sendNativeMessage("parser", { kind: "get-debug-flag" })
        .then(r => {
            DEBUG = r === true;
            try {
                window.postMessage({ __firedown_tt__: 2, debug: DEBUG }, '*');
            } catch (_) {}
        }, () => {});

    const src = browser.runtime.getURL('tiktok-inject.js');
    const s = document.createElement('script');
    s.src = src;
    s.async = false;
    (document.head || document.documentElement || document).appendChild(s);

    // Page-world inject → background bridge.
    window.addEventListener('message', (event) => {
        if (event.source !== window) return;
        const d = event.data;
        if (!d || d.__firedown_tt__ !== 1) return;
        browser.runtime.sendMessage({
            kind: 'tiktok-itemlist',
            url: d.url,
            body: d.body
        }).then(() => {}, () => {});
    });

    // Single-video pages (/@user/video/ID) never fire /api/*item_list/ —
    // TikTok hydrates the player directly from the JSON blob in
    // __UNIVERSAL_DATA_FOR_REHYDRATION__ under
    // __DEFAULT_SCOPE__["webapp.video-detail"].itemInfo.itemStruct.
    // Read that one object and forward it through the same bridge
    // wrapped as a one-item itemList, so the background parser picks
    // it up with no special-casing.
    function captureVideoDetailSSR() {
        try {
            if (!/^\/@[^/]+\/video\/\d+/.test(location.pathname)) return;
            const tag = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
            if (!tag || !tag.textContent) return;
            const data = JSON.parse(tag.textContent);
            const scope = data && data.__DEFAULT_SCOPE__;
            const detail = scope && scope['webapp.video-detail'];
            const item = detail && detail.itemInfo && detail.itemInfo.itemStruct;
            if (!item || !item.video) return;
            console.info('[TT] video-detail SSR captured id=' + item.id);
            browser.runtime.sendMessage({
                kind: 'tiktok-itemlist',
                url: location.href,
                body: JSON.stringify({ itemList: [item] })
            }).then(() => {}, () => {});
        } catch (_) {}
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', captureVideoDetailSSR, { once: true });
    } else {
        captureVideoDetailSSR();
    }

    // Take-A-Break dismiss-in-place. The overlay suppresses /api/*
    // calls until it goes away. Reloading flagged the session and
    // made things worse, so we just close the modal: Escape key first,
    // then a text/aria-label match for the dismiss button.
    let dismissed = false;
    function tryDismissTakeABreak() {
        if (dismissed) return;
        const img = document.querySelector('img[src*="Take_A_Break_Reminder"]');
        if (!img) return;
        try {
            document.dispatchEvent(new KeyboardEvent('keydown', {
                key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
                bubbles: true, cancelable: true
            }));
        } catch (_) {}
        const dismissText = /^\s*(keep watching|continue watching|continue|got it!?|ok(ay)?|dismiss|skip|close|i understand|let me watch|watch on)\s*[.!]?\s*$/i;
        let node = img;
        for (let i = 0; i < 14 && node; i++) {
            const candidates = node.querySelectorAll(
                'button, [role="button"], a[role="button"], [aria-label]'
            );
            for (const btn of candidates) {
                const text = (btn.textContent || '').trim();
                const aria = (btn.getAttribute('aria-label') || '').trim();
                if ((text && dismissText.test(text))
                        || (aria && dismissText.test(aria))) {
                    try { btn.click(); } catch (_) {}
                    dismissed = true;
                    console.info('[TT] Take-A-Break dismissed via "'
                        + (text || aria).slice(0, 40) + '"');
                    return;
                }
            }
            node = node.parentElement;
        }
    }

    // Poll briefly for the overlay (it can mount any time during the
    // first ~6s) and dismiss as soon as it appears.
    const dismissInterval = setInterval(() => {
        tryDismissTakeABreak();
        if (dismissed) clearInterval(dismissInterval);
    }, 400);
    setTimeout(() => clearInterval(dismissInterval), 8000);
})();
