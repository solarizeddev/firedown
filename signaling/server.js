#!/usr/bin/env node
/*
 * Firedown P2P signaling relay — the ONLY server in the "Send directly" flow,
 * and deliberately tiny. It brokers the WebRTC handshake so a shared link works
 * one-way (sender shares a link, receiver taps it, done) even across networks,
 * WITHOUT ever seeing a byte of the file: the file streams peer-to-peer and
 * end-to-end encrypted (DTLS). All this holds is two small text blobs — the
 * offer and the answer — for at most a couple of minutes, then forgets them.
 *
 * It is intentionally dependency-free (Node's http only) so it's trivial to
 * audit and deploy anywhere. Put it behind TLS (nginx / Caddy / Cloudflare) on
 * a host like sig.firedown.app.
 *
 * Protocol (FDS-SIG/1). <id> is a 128-bit base64url token the sender mints.
 *   POST /o/<id>   body = offer code (FDS1.…)    sender uploads the offer
 *   GET  /o/<id>                                  receiver fetches the offer
 *   POST /a/<id>   body = answer code (FDR1.…)    receiver uploads the answer
 *   GET  /a/<id>?wait=1                            sender long-polls for it
 *   GET  /s/<id>                                   human landing + App Link target
 *   GET  /.well-known/assetlinks.json             Android App Link verification
 *   GET  /healthz                                  liveness
 *
 * Everything is first-writer-wins and single-use: once the sender has read the
 * answer, the session is dropped. Sessions also expire on a TTL regardless.
 */

"use strict";

const http = require("http");

// ── config (env-overridable) ───────────────────────────────────────────────
const PORT = parseInt(process.env.PORT || "8787", 10);
const HOST = process.env.HOST || "127.0.0.1"; // bind loopback; TLS terminator in front
const TTL_MS = parseInt(process.env.FDS_TTL_MS || "180000", 10); // 3 min
const MAX_BLOB = parseInt(process.env.FDS_MAX_BLOB || "16384", 10); // 16 KB per blob
const MAX_SESSIONS = parseInt(process.env.FDS_MAX_SESSIONS || "20000", 10);
const LONGPOLL_MS = parseInt(process.env.FDS_LONGPOLL_MS || "25000", 10);
// Where the app is published — used only on the human landing page.
const STORE_URL = process.env.FDS_STORE_URL || "https://firedown.app";
// SHA-256 fingerprints of your app signing cert(s) for App Links, comma-separated
// (get with: keytool -list -v -keystore <ks> | grep SHA256). Optional.
const APP_FINGERPRINTS = (process.env.FDS_APP_FINGERPRINTS || "")
    .split(",").map((s) => s.trim()).filter(Boolean);
const APP_ID = process.env.FDS_APP_ID || "com.solarized.firedown";

// ── in-memory session store ─────────────────────────────────────────────────
// id -> { offer, answer, createdAt, waiters:[] }
const sessions = new Map();

function now() { return Date.now(); }

function sweep() {
  const cutoff = now() - TTL_MS;
  for (const [id, s] of sessions) {
    if (s.createdAt < cutoff) {
      for (const w of s.waiters) { finishWaiter(w, 204); }
      sessions.delete(id);
    }
  }
}
setInterval(sweep, 30000).unref();

function finishWaiter(res, status, body) {
  if (res.__done) { return; }
  res.__done = true;
  if (res.__timer) { clearTimeout(res.__timer); }
  send(res, status, body || "");
}

// ── validation ──────────────────────────────────────────────────────────────
const ID_RE = /^[A-Za-z0-9_-]{16,64}$/;

function validId(id) { return typeof id === "string" && ID_RE.test(id); }

// ── HTTP helpers ──────────────────────────────────────────────────────────────
function send(res, status, body, type) {
  if (res.__done && status !== undefined) { /* long-poll already answered */ }
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(body || "", "utf8");
  res.writeHead(status, {
    "Content-Type": type || "text/plain; charset=utf-8",
    "Content-Length": buf.length,
    "Cache-Control": "no-store",
    // Signaling fetches come from the app's OkHttp (no browser origin), but be
    // permissive so a future in-page fetch also works. No credentials, no cookies.
    "Access-Control-Allow-Origin": "*",
  });
  res.end(buf);
}

function readBody(req, cb) {
  let size = 0;
  const chunks = [];
  req.on("data", (c) => {
    size += c.length;
    if (size > MAX_BLOB) {
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on("end", () => cb(Buffer.concat(chunks).toString("utf8")));
  req.on("error", () => cb(null));
}

// ── landing page (opened only when the App Link didn't capture the link, e.g.
//    Firedown isn't installed). Kept minimal + self-contained. ────────────────
function landingPage(id) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Receive a file · Firedown</title>
<style>
  :root{color-scheme:dark light}
  body{margin:0;min-height:100dvh;display:grid;place-items:center;
    font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
    background:#17120e;color:#f3ede6;text-align:center;padding:24px}
  .card{max-width:340px}
  h1{font-size:22px;margin:0 0 8px}
  p{color:#a99f95;margin:0 0 22px}
  a.btn{display:inline-block;background:#ff6a3d;color:#1a0a04;font-weight:600;
    text-decoration:none;padding:13px 22px;border-radius:22px}
  .muted{font-size:13px;color:#7d746b;margin-top:18px}
</style></head><body><div class="card">
  <h1>Someone's sending you a file</h1>
  <p>Open it in Firedown to receive it — device to device, nothing stored here.</p>
  <a class="btn" href="firedown://p2p/r/${id}?s=${encodeURIComponent(selfOrigin())}">Open in Firedown</a>
  <div class="muted">Don't have it? <a style="color:#ff9c7a" href="${STORE_URL}">Get Firedown</a></div>
</div></body></html>`;
}

let SELF_ORIGIN = process.env.FDS_ORIGIN || ""; // e.g. https://sig.firedown.app
function selfOrigin() { return SELF_ORIGIN; }

function assetLinks() {
  if (APP_FINGERPRINTS.length === 0) { return "[]"; }
  return JSON.stringify([{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: APP_ID,
      sha256_cert_fingerprints: APP_FINGERPRINTS,
    },
  }], null, 2);
}

// ── router ────────────────────────────────────────────────────────────────────
const server = http.createServer((req, res) => {
  // Learn our own origin from the first request if not configured.
  if (!SELF_ORIGIN && req.headers.host) {
    const proto = req.headers["x-forwarded-proto"] || "https";
    SELF_ORIGIN = `${proto}://${req.headers.host}`;
  }

  const url = new URL(req.url, "http://x");
  const path = url.pathname;
  const method = req.method || "GET";

  if (method === "OPTIONS") { return send(res, 204, ""); }
  if (path === "/healthz") { return send(res, 200, "ok"); }
  if (path === "/.well-known/assetlinks.json") {
    return send(res, 200, assetLinks(), "application/json; charset=utf-8");
  }

  // /s/<id> — human landing / App Link target
  let m = path.match(/^\/s\/([^/]+)$/);
  if (m && method === "GET") {
    if (!validId(m[1])) { return send(res, 404, "not found"); }
    return send(res, 200, landingPage(m[1]), "text/html; charset=utf-8");
  }

  // /o/<id> — offer
  m = path.match(/^\/o\/([^/]+)$/);
  if (m) {
    const id = m[1];
    if (!validId(id)) { return send(res, 400, "bad id"); }
    if (method === "POST") {
      if (sessions.size >= MAX_SESSIONS) { return send(res, 503, "busy"); }
      return readBody(req, (body) => {
        if (body == null) { return send(res, 413, "too large"); }
        if (!body.startsWith("FDS1.")) { return send(res, 400, "bad offer"); }
        let s = sessions.get(id);
        if (!s) { s = { offer: null, answer: null, createdAt: now(), waiters: [] }; sessions.set(id, s); }
        s.offer = body;
        return send(res, 200, "ok");
      });
    }
    if (method === "GET") {
      const s = sessions.get(id);
      if (!s || !s.offer) { return send(res, 404, "not found"); }
      return send(res, 200, s.offer);
    }
    return send(res, 405, "method not allowed");
  }

  // /a/<id> — answer
  m = path.match(/^\/a\/([^/]+)$/);
  if (m) {
    const id = m[1];
    if (!validId(id)) { return send(res, 400, "bad id"); }
    const s = sessions.get(id);
    if (method === "POST") {
      if (!s) { return send(res, 404, "no such session"); }
      if (s.answer) { return send(res, 409, "already answered"); }
      return readBody(req, (body) => {
        if (body == null) { return send(res, 413, "too large"); }
        if (!body.startsWith("FDR1.")) { return send(res, 400, "bad answer"); }
        s.answer = body;
        // Release any long-pollers waiting for it.
        const waiters = s.waiters.splice(0);
        for (const w of waiters) { finishWaiter(w, 200, body); }
        // If a sender was parked on the poll, it just got the answer — the
        // session is spent (single-use). Otherwise keep it for the next GET,
        // which delivers + deletes.
        if (waiters.length > 0) { sessions.delete(id); }
        return send(res, 200, "ok");
      });
    }
    if (method === "GET") {
      if (!s) { return send(res, 404, "no such session"); }
      if (s.answer) {
        const answer = s.answer;
        sessions.delete(id); // single-use: sender has it, we're done
        return send(res, 200, answer);
      }
      if (url.searchParams.get("wait") === "1") {
        // Long-poll: park until an answer arrives or we time out.
        res.__timer = setTimeout(() => {
          const i = s.waiters.indexOf(res);
          if (i >= 0) { s.waiters.splice(i, 1); }
          finishWaiter(res, 204, "");
        }, LONGPOLL_MS);
        res.__timer.unref();
        s.waiters.push(res);
        req.on("close", () => {
          const i = s.waiters.indexOf(res);
          if (i >= 0) { s.waiters.splice(i, 1); }
          res.__done = true;
          if (res.__timer) { clearTimeout(res.__timer); }
        });
        return;
      }
      return send(res, 204, "");
    }
    return send(res, 405, "method not allowed");
  }

  return send(res, 404, "not found");
});

server.listen(PORT, HOST, () => {
  // No request/blob logging by design — this service is a blind relay.
  console.log(`firedown-signaling listening on ${HOST}:${PORT} (ttl=${TTL_MS}ms)`);
});
