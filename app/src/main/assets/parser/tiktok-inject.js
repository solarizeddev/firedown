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
    console.log('[TT-INJECT] page hook installing, fetch=' + (typeof window.fetch) + ' XHR=' + (typeof XMLHttpRequest));

    function emit(url, body) {
        console.log('[TT-INJECT] emit url=' + (url || '').slice(0, 120) + ' bodyLen=' + (body ? body.length : 0));
        try {
            window.postMessage({ __firedown_tt__: 1, url, body }, '*');
        } catch (e) {
            console.log('[TT-INJECT] postMessage failed:', e && e.message);
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
                console.log('[TT-INJECT] fetch match url=' + url.slice(0, 120) + ' ok=' + resp.ok + ' status=' + resp.status);
                if (!resp.ok) return;
                resp.clone().text().then(t => emit(url, t), e => {
                    console.log('[TT-INJECT] fetch clone.text failed:', e && e.message);
                });
            }, () => {});
            return p;
        };
        console.log('[TT-INJECT] fetch hook installed');
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
            console.log('[TT-INJECT] xhr match url=' + url.slice(0, 120));
            this.addEventListener('load', () => {
                console.log('[TT-INJECT] xhr load status=' + this.status + ' url=' + url.slice(0, 80));
                if (this.status >= 200 && this.status < 300) {
                    emit(url, this.responseText);
                }
            }, { once: true });
        }
        return origSend.apply(this, arguments);
    };
    console.log('[TT-INJECT] xhr hook installed');
})();
