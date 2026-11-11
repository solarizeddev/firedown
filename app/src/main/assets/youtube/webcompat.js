/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * webcompat.js: two of Firefox's webcompat interventions for YouTube
 * (browser/extensions/webcompat, mozilla-firefox/firefox).
 *
 * 1. increase_performance_now_precision.js (bugs 1842437, 1964603, "YouTube
 *    back/forward fix"): consecutive performance.now() readings that come
 *    back equal (timer precision is reduced) are made unique. Page world.
 *
 * 2. The idle keep-alive of bug2060611-youtube.com-background-video-playback.js:
 *    during long background playback YouTube shows "Video paused. Continue
 *    watching?". While the page is really hidden, an Alt keydown/keyup (a key
 *    that does nothing on its own) is sent once a minute. Content-script
 *    world, so document.hidden is the real visibility, not the page world's
 *    override in content.js.
 *
 * Top frame of www, m and music.youtube.com only; never the token page
 * (robots.txt), whose timing and events belong to the attestation.
 */

if (window === window.top
        && /^(www|m|music)\.youtube\.com$/.test(location.hostname)
        && location.pathname !== '/robots.txt') {

    const s = document.createElement('script');
    s.textContent = `(function(){
        if (window.__fdPerfNowFix) return;
        Object.defineProperty(window, '__fdPerfNowFix', { value: true });
        if (!String([performance.now(), performance.now()][1]).includes('.')) return;
        let counter = 0;
        let previousVal = 0;
        const perf = Object.getPrototypeOf(performance);
        const now = perf.now;
        perf.now = function () {
            let originalVal = now.call(this);
            if (originalVal === previousVal) {
                originalVal += 0.00000003 * ++counter;
            } else {
                previousVal = originalVal;
                counter = 0;
            }
            return originalVal;
        };
    })();`;
    (document.head || document.documentElement).appendChild(s);
    s.remove();

    window.setInterval(() => {
        if (!document.hidden) {
            return;
        }
        for (const type of ['keydown', 'keyup']) {
            document.dispatchEvent(new KeyboardEvent(type, {
                bubbles: true,
                cancelable: true,
                keyCode: 18, // Alt
                which: 18,
            }));
        }
    }, 60 * 1000);
}
