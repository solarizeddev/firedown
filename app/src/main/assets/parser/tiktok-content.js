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

    console.log('[TT-CONTENT] loaded at', location.href);

    const src = browser.runtime.getURL('tiktok-inject.js');
    const s = document.createElement('script');
    s.src = src;
    s.async = false;
    s.onload = () => console.log('[TT-CONTENT] inject loaded');
    s.onerror = (e) => console.log('[TT-CONTENT] inject failed to load:', e && e.message);
    (document.head || document.documentElement || document).appendChild(s);
    console.log('[TT-CONTENT] inject element appended, src=' + src);

    window.addEventListener('message', (event) => {
        if (event.source !== window) return;
        const d = event.data;
        if (!d || d.__firedown_tt__ !== 1) return;
        console.log('[TT-CONTENT] postMessage received url=' + (d.url || '').slice(0, 120) + ' bodyLen=' + (d.body ? d.body.length : 0));
        browser.runtime.sendMessage({
            kind: 'tiktok-itemlist',
            url: d.url,
            body: d.body
        }).then(r => {
            console.log('[TT-CONTENT] sendMessage ack', r);
        }).catch(e => {
            console.log('[TT-CONTENT] sendMessage failed:', e && e.message);
        });
    });
    console.log('[TT-CONTENT] message bridge listening');
})();
