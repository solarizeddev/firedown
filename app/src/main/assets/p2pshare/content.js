/* Firedown P2P share — bridge content script.
 *
 * Runs in the hidden engine GeckoSession on the loopback page
 * http://127.0.0.1:<port>/engine (matched by manifest, gated to /engine below).
 * The WebRTC engine itself is a PAGE-WORLD script (engine-page.js) because
 * WebRTC only works in a real content document; a page script has no
 * browser.runtime, so this content script is the ONLY piece that can reach
 * Java. It does two thin jobs:
 *   1. Opens the native "p2pshare" port (connectNative) to P2pShareController.
 *   2. Relays messages both ways between the page (window.postMessage) and the
 *      port — page evt -> port -> Java; Java cmd -> port -> page.
 * No WebRTC, no file bytes here (bytes ride the loopback fetch/POST directly).
 */

"use strict";

// Gate to our engine page only. The match pattern is http://127.0.0.1/* (Firefox
// match patterns ignore the port), so a user browsing some other localhost page
// would also inject this — do nothing unless it's our engine path.
if (window.location.pathname === "/engine") {
  (function () {
    const PORT_NAME = "p2pshare";
    let port = null;
    let debug = false;
    let initSent = false;

    // Handshake: give the page the debug flag; it replies with "ready".
    // Idempotent — both the port-connect path and a late "hello" can call it,
    // but the page must only be initialised (and only post "ready") once.
    function sendInit() {
      if (initSent) { return; }
      initSent = true;
      window.postMessage({ p2p: "cmd", data: { type: "__init", debug: debug } }, window.location.origin);
    }

    // Page -> Java: forward engine events to the native port.
    window.addEventListener("message", (ev) => {
      if (ev.source !== window || !ev.data) { return; }
      if (ev.data.p2p === "hello") {
        // Secondary trigger only. The page-world script runs during parse and
        // posts "hello" BEFORE this content script (run_at document_end)
        // attaches its listener, so this "hello" is normally already missed —
        // the connect() path below is what actually drives the handshake. Kept
        // as a belt-and-suspenders for any injection-order edge case.
        if (port) { sendInit(); }
        return;
      }
      if (ev.data.p2p === "evt" && port) {
        try { port.postMessage(ev.data.data); } catch (e) { /* port gone */ }
      }
    });

    function connect() {
      port = browser.runtime.connectNative(PORT_NAME);
      // Java -> page: forward commands from the port into the page world.
      port.onMessage.addListener((msg) => {
        window.postMessage({ p2p: "cmd", data: msg }, window.location.origin);
      });
      port.onDisconnect.addListener(() => { port = null; });
      // Drive the handshake unconditionally: the page-world engine script has
      // already executed (during parse, before this document_end script) so
      // its "message" listener is guaranteed live — no need to wait for the
      // "hello" we most likely missed. sendInit() is initSent-guarded, so a
      // later "hello" can't produce a second __init / duplicate "ready".
      sendInit();
    }

    // Resolve the debug flag (BuildConfig.DEBUG), then open the port.
    browser.runtime.sendNativeMessage(PORT_NAME, { kind: "get-debug-flag" })
      .then((r) => { debug = (r === true); }, () => {})
      .then(connect, connect);
  })();
}
