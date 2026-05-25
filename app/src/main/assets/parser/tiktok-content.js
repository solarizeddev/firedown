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

    // Inline script that runs in the page world. Patches fetch and
    // XMLHttpRequest with passive observers — never mutates the
    // response, only clones it for our consumption.
    const PAGE_HOOK = `(() => {
        'use strict';
        const PAT = /\\/api\\/[a-z_]+\\/item_list\\/?\\?/i;
        const emit = (url, body) => {
            try { window.postMessage({ __firedown_tt__: 1, url, body }, '*'); } catch (_) {}
        };
        if (typeof window.fetch === 'function') {
            const orig = window.fetch;
            window.fetch = function(input, init) {
                const p = orig.apply(this, arguments);
                p.then(resp => {
                    if (!resp || !resp.ok) return;
                    const url = typeof input === 'string' ? input : input && input.url;
                    if (!url || !PAT.test(url)) return;
                    resp.clone().text().then(t => emit(url, t), () => {});
                }, () => {});
                return p;
            };
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
                this.addEventListener('load', () => {
                    if (this.status >= 200 && this.status < 300) {
                        emit(url, this.responseText);
                    }
                }, { once: true });
            }
            return origSend.apply(this, arguments);
        };
    })();`;

    const s = document.createElement('script');
    s.textContent = PAGE_HOOK;
    (document.head || document.documentElement || document).appendChild(s);
    s.remove();

    window.addEventListener('message', (event) => {
        if (event.source !== window) return;
        const d = event.data;
        if (!d || d.__firedown_tt__ !== 1) return;
        browser.runtime.sendMessage({
            kind: 'tiktok-itemlist',
            url: d.url,
            body: d.body
        }).catch(() => {});
    });
})();
