// Runs in TikTok's page world. Loaded via <script src="moz-extension://..."> from
// tiktok-content.js so it bypasses TikTok's CSP (extension resources are
// exempt from the page's content security policy because they belong to
// a different origin). An inline <script> with textContent would be
// blocked by `script-src 'self'` — that's why the previous inline
// inject silently failed to execute.
//
// Passively observes /api/*/item_list/ responses flowing through
// window.fetch and XMLHttpRequest. Posts the body to the content script
// via window.postMessage. NEVER mutates the response; the page receives
// bytes unchanged.
(() => {
    'use strict';

    const PAT = /\/api\/[a-z_]+\/item_list\/?\?/i;

    // Page-world script — can't reach browser.*. The content-script
    // fetches BuildConfig.DEBUG from Java and forwards it via the
    // same postMessage channel (__firedown_tt__: 2). Defaults to false
    // so release builds skip every console.log argument evaluation
    // below until/unless the content script tells us otherwise.
    let DEBUG = false;
    const log = (...args) => { if (DEBUG) console.log(...args); };
    window.addEventListener('message', (event) => {
        if (event.source !== window) return;
        const d = event.data;
        if (d && d.__firedown_tt__ === 2) DEBUG = !!d.debug;
    });

    log('[TT-INJECT] page hook installing, fetch=' + (typeof window.fetch) + ' XHR=' + (typeof XMLHttpRequest));

    function emit(url, body) {
        // TikTok routinely fires empty preflight/cache-warm responses
        // on the same /api/*/item_list/ endpoints. They have no
        // itemList[] to parse, so don't pay the postMessage +
        // runtime.sendMessage + JSON.parse round-trip just to drop
        // them on the background side.
        if (!body) return;
        log('[TT-INJECT] emit url=' + (url || '').slice(0, 120) + ' bodyLen=' + body.length);
        try {
            window.postMessage({ __firedown_tt__: 1, url, body }, '*');
        } catch (e) {
            log('[TT-INJECT] postMessage failed:', e && e.message);
        }
    }

    if (typeof window.fetch === 'function') {
        const orig = window.fetch;
        window.fetch = function(input, init) {
            const p = orig.apply(this, arguments);
            p.then(resp => {
                if (!resp) return;
                const url = typeof input === 'string' ? input : input && input.url;
                if (!url || !PAT.test(url)) return;
                log('[TT-INJECT] fetch match url=' + url.slice(0, 120) + ' ok=' + resp.ok + ' status=' + resp.status);
                if (!resp.ok) return;
                resp.clone().text().then(t => emit(url, t), e => {
                    log('[TT-INJECT] fetch clone.text failed:', e && e.message);
                });
            }, () => {});
            return p;
        };
        log('[TT-INJECT] fetch hook installed');
    }

    const xp = XMLHttpRequest.prototype;
    const origOpen = xp.open;
    const origSend = xp.send;
    xp.open = function(method, url) {
        try { this.__fd_url = String(url); } catch (_) {}
        return origOpen.apply(this, arguments);
    };
    xp.send = function() {
        const url = this.__fd_url;
        if (url && PAT.test(url)) {
            log('[TT-INJECT] xhr match url=' + url.slice(0, 120));
            this.addEventListener('load', () => {
                log('[TT-INJECT] xhr load status=' + this.status + ' url=' + url.slice(0, 80));
                if (this.status >= 200 && this.status < 300) {
                    emit(url, this.responseText);
                }
            }, { once: true });
        }
        return origSend.apply(this, arguments);
    };
    log('[TT-INJECT] xhr hook installed');
})();
