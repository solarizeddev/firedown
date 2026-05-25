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
(() => {
    'use strict';

    // DEBUG flag bridged from BuildConfig.DEBUG via the parser
    // native channel. Defaults to false so release builds don't pay
    // the per-call argument-evaluation cost.
    let DEBUG = false;
    const log = (...args) => { if (DEBUG) console.log(...args); };
    browser.runtime.sendNativeMessage("parser", { kind: "get-debug-flag" })
        .then(r => {
            DEBUG = !!(r && r.debug);
            // Page-world inject can't read browser.* — forward the
            // flag via window.postMessage so its own DEBUG flips on
            // the same channel we already use for body emission.
            try {
                window.postMessage({ __firedown_tt__: 2, debug: DEBUG }, '*');
            } catch (_) {}
        })
        .catch(() => {});

    log('[TT-CONTENT] loaded at', location.href);

    const src = browser.runtime.getURL('tiktok-inject.js');
    const s = document.createElement('script');
    s.src = src;
    s.async = false;
    s.onload = () => log('[TT-CONTENT] inject loaded');
    s.onerror = (e) => log('[TT-CONTENT] inject failed to load:', e && e.message);
    (document.head || document.documentElement || document).appendChild(s);
    log('[TT-CONTENT] inject element appended, src=' + src);

    window.addEventListener('message', (event) => {
        if (event.source !== window) return;
        const d = event.data;
        if (!d || d.__firedown_tt__ !== 1) return;
        log('[TT-CONTENT] postMessage received url=' + (d.url || '').slice(0, 120) + ' bodyLen=' + (d.body ? d.body.length : 0));
        browser.runtime.sendMessage({
            kind: 'tiktok-itemlist',
            url: d.url,
            body: d.body
        }).then(r => {
            log('[TT-CONTENT] sendMessage ack', r);
        }).catch(e => {
            log('[TT-CONTENT] sendMessage failed:', e && e.message);
        });
    });
    log('[TT-CONTENT] message bridge listening');
})();
