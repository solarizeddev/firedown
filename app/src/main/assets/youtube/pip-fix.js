/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * pip-fix.js: mobile YouTube in a picture-in-picture window.
 *
 * Ported from Firefox for Android's webcompat intervention for bug 1956165
 * (browser/extensions/webcompat/injections/js/
 * bug1956165-www.youtube.com-picture-in-picture-fix.js, mozilla-firefox/firefox).
 *
 * The mobile player reads the window's outer size shrinking under a third of
 * the screen as the app going to the background: it leaves fullscreen and
 * pauses, a quarter second into the PiP window. While the window is that
 * small the page sees the screen's size as the outer size, and
 * exitFullscreen() does nothing. Runs in the page world, top frame of
 * m.youtube.com, at document_start (as Firefox's does).
 */

if (location.hostname === 'm.youtube.com' && window === window.top) {
    const s = document.createElement('script');
    s.textContent = `(function(){
        if (window.__fdPipFix) return;
        Object.defineProperty(window, '__fdPipFix', { value: true });
        const w = Object.getOwnPropertyDescriptor(window, 'outerWidth');
        const h = Object.getOwnPropertyDescriptor(window, 'outerHeight');
        if (!w || !h || !w.get || !h.get) return;
        const outerW = w.get, outerH = h.get;
        let ratio;
        function pipRatio() {
            if (ratio !== undefined) {
                return ratio;
            }
            // Asked before the page's config exists: the default, not cached,
            // so a later call still reads the page's own threshold.
            if (!window.ytcfg || typeof window.ytcfg.get !== 'function') {
                return 0.33;
            }
            let flag;
            try {
                const cfg = window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS');
                const exp = cfg && cfg.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH
                    && cfg.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH.serializedExperimentFlags;
                const m = exp && exp.match(/html5_picture_in_picture_logging_onresize_ratio=(\\d+(\\.\\d+)?)/);
                flag = m && parseFloat(m[1]);
            } catch (e) {}
            ratio = flag || 0.33;
            return ratio;
        }
        let largest = 0;
        function inPip() {
            const screenArea = screen.width * screen.height;
            const outerArea = outerW.call(window) * outerH.call(window);
            largest = Math.max(largest, screenArea, outerArea);
            return outerArea / largest < pipRatio();
        }
        w.get = function () { return inPip() ? screen.width : outerW.call(window); };
        h.get = function () { return inPip() ? screen.height : outerH.call(window); };
        Object.defineProperty(window, 'outerWidth', w);
        Object.defineProperty(window, 'outerHeight', h);
        const exit = Document.prototype.exitFullscreen;
        Object.defineProperty(Document.prototype, 'exitFullscreen', {
            value: function () { return inPip() ? undefined : exit.apply(this, arguments); },
            writable: true,
            configurable: true,
        });
    })();`;
    (document.head || document.documentElement).appendChild(s);
    s.remove();
}
