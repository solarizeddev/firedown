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
(() => {
    'use strict';

    console.log('[TT-CONTENT] loaded at', location.href);

    // Inline script that runs in the page world. Patches fetch and
    // XMLHttpRequest with passive observers — never mutates the
    // response, only clones it for our consumption.
    const PAGE_HOOK = `(() => {
        'use strict';
        const PAT = /\\/api\\/[a-z_]+\\/item_list\\/?\\?/i;
        console.log('[TT-INJECT] page hook installing, fetch=' + (typeof window.fetch) + ' XHR=' + (typeof XMLHttpRequest));
        const emit = (url, body) => {
            console.log('[TT-INJECT] emit url=' + url.slice(0, 120) + ' bodyLen=' + (body ? body.length : 0));
            try { window.postMessage({ __firedown_tt__: 1, url, body }, '*'); } catch (e) {
                console.log('[TT-INJECT] postMessage failed:', e && e.message);
            }
        };
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
    })();`;

    const s = document.createElement('script');
    s.textContent = PAGE_HOOK;
    (document.head || document.documentElement || document).appendChild(s);
    s.remove();
    console.log('[TT-CONTENT] page hook injected');

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
