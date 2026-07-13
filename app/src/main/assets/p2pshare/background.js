/* Firedown P2P share engine — WebRTC DataChannel transfer, driven by Java.
 *
 * Architecture (why it looks like this):
 * - This background page owns the RTCPeerConnection + DataChannel. Java owns
 *   ALL UI and the byte endpoints. The two talk over ONE long-lived native
 *   port ("p2pshare", connectNative below) — the PoTokenGenerator pattern:
 *   Java initiates commands, we reply with events. Nothing here runs until a
 *   share screen sends a command; at boot the page just connects the port and
 *   sits idle.
 * - File bytes NEVER cross the native-messaging bridge (that would mean
 *   base64-in-JSON for every chunk). Instead Java runs a loopback HTTP server
 *   (P2pLoopbackServer, 127.0.0.1, token-gated): the SENDER side fetch()es
 *   the file from it and pumps the stream into the DataChannel; the RECEIVER
 *   side POSTs arriving chunks back to it, which writes them to disk.
 * - Signaling is OFFLINE: the offer/answer are compressed into short codes
 *   (FDS1./FDR1. + base64url(deflate-raw(JSON))) that travel as QR scans or
 *   through any messenger via the share sheet. There is no signaling server;
 *   the only external party is the STUN server Java passes in (user-chosen,
 *   Settings), and none at all when both peers share a LAN. Non-trickle ICE:
 *   we wait for gathering to complete so ONE code carries everything.
 *
 * RTCPeerConnection is Pref-gated WebIDL ("media.peerconnection.enabled"),
 * and the gate is evaluated when a page GLOBAL is created — so if this page
 * loaded while the user's WebRTC toggle was off (the default), flipping the
 * pref later does NOT make the constructor appear here. Java handles that:
 * it enables the pref for the share session, sends {type:"ensure"}, and when
 * we answer ok:false we location.reload() — the reloaded page re-creates its
 * global with the pref now on, reconnects the port, and posts a fresh
 * {type:"ready", rtc:true}. Never remove the ensure/reload dance. (Java's
 * side tolerates the reload's port disconnect — see P2pShareController.)
 *
 * Port protocol (Java -> JS commands):
 *   {type:"ensure"}                                    probe/repair RTC availability
 *   {type:"send-start", readUrl, name, size, mime, device, stun}
 *   {type:"send-answer", code}                         the scanned FDR1. reply
 *   {type:"recv-start", code, stun}                    the scanned FDS1. offer
 *   {type:"recv-accept", writeUrl}                     user accepted the preview
 *   {type:"stop"}                                      tear everything down
 * (JS -> Java events):
 *   {type:"ready", rtc}                                port (re)connected
 *   {type:"ensure-result", ok}
 *   {type:"code", role:"offer"|"answer", code}
 *   {type:"offer-parsed", name, size, mime, device}    receiver preview data
 *   {type:"state", state}                              connecting|connected|closed|failed
 *   {type:"progress", done, total, rate}               throttled, bytes + bytes/s
 *   {type:"done", role:"send"|"receive", bytes}
 *   {type:"error", code, detail}                       "bad-code" is soft (session survives)
 */

"use strict";

const PORT_NAME = "p2pshare";

// Debug flag, resolved from BuildConfig.DEBUG at boot (get-debug-flag is
// answered before the nativeApp switch in GeckoRuntimeHelper, so any name
// works). Release builds log nothing — logging discipline.
let DEBUG = false;
browser.runtime.sendNativeMessage(PORT_NAME, { kind: "get-debug-flag" })
  .then((r) => { DEBUG = (r === true); }, () => {});

function log(...args) {
  if (!DEBUG) { return; }
  console.log("[P2P]", ...args);
}

/* ── transfer tuning ──────────────────────────────────────────────────────
 * CHUNK: 64 KB is safely under every negotiated SCTP max-message-size.
 * HIGH/LOW water: pause the sender pump when the DataChannel has buffered
 * 4 MB, resume at 512 KB (bufferedamountlow) — bounds engine memory while
 * keeping the pipe full on fast paths.
 * FLUSH: the receiver batches arriving chunks to ~4 MB per loopback POST so
 * disk writes aren't per-chunk; posts are strictly sequential (the write
 * endpoint verifies the offset) so ordering on disk is guaranteed.
 * CONNECT_TIMEOUT: with no TURN relay by design, a CGNAT<->CGNAT pair will
 * never connect — fail honestly instead of spinning.
 * ACK_TIMEOUT: after the sender sends eof it waits for the receiver's disk-
 * write ack; bound the wait so a dropped ack can't hang the sender forever.
 * OVERRUN_SLACK: the receiver refuses to write materially more than the size
 * the user accepted (a malicious sender can't fill the disk past the preview).
 */
const CHUNK = 64 * 1024;
const BUFFER_HIGH = 4 * 1024 * 1024;
const BUFFER_LOW = 512 * 1024;
const FLUSH_BYTES = 4 * 1024 * 1024;
const CONNECT_TIMEOUT_MS = 20000;
const GATHER_TIMEOUT_MS = 5000;
const PROGRESS_INTERVAL_MS = 400;
const ACK_TIMEOUT_MS = 30000;
const DRAIN_TIMEOUT_MS = 5000;
const OVERRUN_SLACK = 1024 * 1024;

const OFFER_PREFIX = "FDS1.";
const ANSWER_PREFIX = "FDR1.";

let port = null;

// The single active session (send OR receive) — the share screens are modal,
// one transfer at a time by design.
let session = null;

/* ── code codec ──────────────────────────────────────────────────────────
 * deflate-raw keeps an SDP-bearing offer at ~1-2 KB so it fits a scannable
 * QR. CompressionStream is available in this GeckoView; if it ever isn't,
 * "n" (none) codes fall back to plain UTF-8 — bigger QR, same protocol. */

function bytesToBase64Url(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) {
    bin += String.fromCharCode(bytes[i]);
  }
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlToBytes(text) {
  let b64 = text.replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4 !== 0) { b64 += "="; }
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    bytes[i] = bin.charCodeAt(i);
  }
  return bytes;
}

async function pipeThrough(bytes, stream) {
  const result = new Response(new Blob([bytes]).stream().pipeThrough(stream));
  return new Uint8Array(await result.arrayBuffer());
}

async function encodeCode(prefix, payload) {
  const plain = new TextEncoder().encode(JSON.stringify(payload));
  if (typeof CompressionStream === "undefined") {
    return prefix + "n" + bytesToBase64Url(plain);
  }
  const packed = await pipeThrough(plain, new CompressionStream("deflate-raw"));
  return prefix + "d" + bytesToBase64Url(packed);
}

// Throws on ANY malformed code (bad prefix/mode, bad base64 via atob's
// DOMException, corrupt deflate via the stream's TypeError, non-JSON body).
// Callers treat every throw here as a SOFT "bad-code" — the session's own
// QR is still valid, the user just re-scans/re-pastes.
async function decodeCode(prefix, code) {
  const trimmed = code.trim();
  if (!trimmed.startsWith(prefix)) {
    throw new Error("bad-code");
  }
  const mode = trimmed.charAt(prefix.length);
  const body = base64UrlToBytes(trimmed.slice(prefix.length + 1));
  let plain;
  if (mode === "d") {
    plain = await pipeThrough(body, new DecompressionStream("deflate-raw"));
  } else if (mode === "n") {
    plain = body;
  } else {
    throw new Error("bad-code");
  }
  return JSON.parse(new TextDecoder().decode(plain));
}

/* ── WebRTC helpers ────────────────────────────────────────────────────── */

async function newPeerConnection(stun) {
  // A single STUN entry on purpose: listing several servers makes ICE query
  // ALL of them in parallel — every share would leak the user's IP to every
  // fallback. Java owns the sequential-fallback policy and passes ONE url.
  // A malformed url would throw in the constructor; Java validates the
  // custom entry, and an empty/blank value means "no STUN" (LAN-only).
  const config = {};
  if (stun && /^stuns?:/i.test(stun)) {
    config.iceServers = [{ urls: stun }];
  }
  // Pre-generate the DTLS certificate explicitly. createOffer otherwise
  // generates one implicitly; if that path is what stalls in this embedding,
  // supplying an explicit cert bypasses it — and the logging isolates cert
  // generation (crypto) from the transport setup as the hang point.
  try {
    log("generating certificate");
    const cert = await RTCPeerConnection.generateCertificate({ name: "ECDSA", namedCurve: "P-256" });
    config.certificates = [cert];
    log("certificate ready");
  } catch (e) {
    log("certificate generation failed", e);
  }
  return new RTCPeerConnection(config);
}

// Non-trickle: resolve once gathering completes so the emitted code carries
// the full candidate set. The timeout returns whatever gathered so far —
// host candidates alone still connect same-LAN pairs even if STUN is
// unreachable (privacy setting pointing at a dead server, offline LAN).
function waitIceComplete(pc) {
  return new Promise((resolve) => {
    if (pc.iceGatheringState === "complete") {
      resolve();
      return;
    }
    const timer = setTimeout(() => {
      log("ice gathering timeout, using partial candidate set");
      resolve();
    }, GATHER_TIMEOUT_MS);
    pc.addEventListener("icegatheringstatechange", () => {
      if (pc.iceGatheringState === "complete") {
        clearTimeout(timer);
        resolve();
      }
    });
  });
}

function watchConnection(s) {
  const pc = s.pc;
  s.connectTimer = setTimeout(() => {
    if (pc.connectionState !== "connected") {
      fail(s, "no-path", "connect timeout");
    }
  }, CONNECT_TIMEOUT_MS);
  pc.addEventListener("connectionstatechange", () => {
    log("connectionState:", pc.connectionState);
    if (s !== session) { return; }
    if (pc.connectionState === "connected") {
      clearTimeout(s.connectTimer);
      post({ type: "state", state: "connected" });
    } else if (pc.connectionState === "failed") {
      fail(s, "no-path", "connection failed");
    } else if (pc.connectionState === "connecting") {
      post({ type: "state", state: "connecting" });
    }
  });
}

// Resolve once the channel's outgoing buffer has drained (the peer's SCTP
// has acked it), so a tiny control message (the receiver's "rcvd" ack) is
// actually on the wire before we tear the transport down. Bounded so a
// wedged channel can't hang teardown.
function waitBufferedDrain(dc) {
  return new Promise((resolve) => {
    if (dc.bufferedAmount === 0) {
      resolve();
      return;
    }
    const timer = setTimeout(resolve, DRAIN_TIMEOUT_MS);
    const prev = dc.bufferedAmountLowThreshold;
    dc.bufferedAmountLowThreshold = 0;
    dc.addEventListener("bufferedamountlow", () => {
      if (dc.bufferedAmount === 0) {
        clearTimeout(timer);
        dc.bufferedAmountLowThreshold = prev;
        resolve();
      }
    });
  });
}

/* ── progress ──────────────────────────────────────────────────────────── */

function makeProgress(total) {
  return { total: total, done: 0, lastPost: 0, lastBytes: 0, lastTime: Date.now() };
}

function postProgress(s, force) {
  const p = s.progress;
  const now = Date.now();
  if (!force && now - p.lastPost < PROGRESS_INTERVAL_MS) { return; }
  const dt = (now - p.lastTime) / 1000;
  const rate = dt > 0 ? Math.max(0, (p.done - p.lastBytes) / dt) : 0;
  p.lastPost = now;
  p.lastBytes = p.done;
  p.lastTime = now;
  post({ type: "progress", done: p.done, total: p.total, rate: Math.round(rate) });
}

/* ── session teardown ──────────────────────────────────────────────────── */

function closeSession(s) {
  if (!s) { return; }
  s.stopped = true;
  clearTimeout(s.connectTimer);
  clearTimeout(s.ackTimer);
  if (s.reader) {
    try { s.reader.cancel(); } catch (e) { /* already done */ }
    s.reader = null;
  }
  if (s.dc) {
    try { s.dc.close(); } catch (e) { /* already closed */ }
    s.dc = null;
  }
  if (s.pc) {
    try { s.pc.close(); } catch (e) { /* already closed */ }
    s.pc = null;
  }
  if (s === session) {
    session = null;
  }
}

function fail(s, code, detail) {
  if (!s || s.stopped) { return; }
  log("error:", code, detail);
  closeSession(s);
  post({ type: "error", code: code, detail: String(detail || "") });
}

// Soft error: report but keep the session (and its still-valid QR) alive.
function softError(detail) {
  log("soft error:", detail);
  post({ type: "error", code: "bad-code", detail: String(detail || "") });
}

/* ── sender ────────────────────────────────────────────────────────────── */

async function startSend(msg) {
  closeSession(session);
  const s = {
    role: "send",
    readUrl: msg.readUrl,
    size: msg.size,
    progress: makeProgress(msg.size),
    stopped: false,
    acked: false,
  };
  session = s;
  try {
    s.pc = await newPeerConnection(msg.stun);
    // Reliable + ordered (defaults): the file must arrive byte-exact.
    s.dc = s.pc.createDataChannel("file");
    s.dc.binaryType = "arraybuffer";
    s.dc.bufferedAmountLowThreshold = BUFFER_LOW;
    s.dc.onopen = () => { pumpFile(s); };
    s.dc.onmessage = (ev) => {
      // The receiver's flush-complete ack — the only control message the
      // sender expects. "done" only after the ack: bufferedAmount hitting 0
      // proves the bytes left OUR buffer, not that they reached disk.
      if (typeof ev.data === "string") {
        let ack = null;
        try { ack = JSON.parse(ev.data); } catch (e) { /* ignore junk */ }
        if (ack && ack.t === "rcvd" && s === session) {
          s.acked = true;
          clearTimeout(s.ackTimer);
          s.progress.done = s.size;
          postProgress(s, true);
          post({ type: "done", role: "send", bytes: s.size });
          closeSession(s);
        }
      }
    };
    watchConnection(s);

    log("creating offer");
    const offer = await s.pc.createOffer();
    log("offer created, setting local description");
    await s.pc.setLocalDescription(offer);
    log("local description set, gathering ICE");
    await waitIceComplete(s.pc);
    log("ICE gathering done");
    if (s !== session || s.stopped) { return; }

    // The code carries the metadata too — the receiver previews name/size
    // BEFORE anything connects, and the accept happens offline.
    const code = await encodeCode(OFFER_PREFIX, {
      v: 1,
      sdp: s.pc.localDescription.sdp,
      name: msg.name,
      size: msg.size,
      mime: msg.mime,
      dev: msg.device,
    });
    if (s !== session || s.stopped) { return; }
    post({ type: "code", role: "offer", code: code });
  } catch (e) {
    fail(s, "engine", e);
  }
}

async function acceptAnswer(msg) {
  const s = session;
  if (!s || s.role !== "send" || !s.pc) { return; }
  // Only meaningful in have-local-offer; a duplicate paste (the button stays
  // live until "connecting" hides it) is a harmless no-op, NOT an error.
  if (s.pc.signalingState !== "have-local-offer") {
    log("ignoring answer in state", s.pc.signalingState);
    return;
  }
  let payload;
  try {
    payload = await decodeCode(ANSWER_PREFIX, msg.code);
  } catch (e) {
    // Any unreadable code is soft — the offer QR is still valid, re-scan.
    softError("answer code unreadable");
    return;
  }
  if (s !== session || s.stopped) { return; }
  try {
    await s.pc.setRemoteDescription({ type: "answer", sdp: payload.sdp });
    post({ type: "state", state: "connecting" });
  } catch (e) {
    // A decodable-but-unapplicable answer (mismatched/corrupt SDP) before we
    // ever connect is still recoverable: the offer stands, treat as soft.
    softError("answer not applicable");
  }
}

async function pumpFile(s) {
  log("datachannel open, pumping", s.size, "bytes");
  try {
    const response = await fetch(s.readUrl);
    if (!response.ok || !response.body) {
      throw new Error("loopback read " + response.status);
    }
    s.reader = response.body.getReader();
    for (;;) {
      if (s !== session || s.stopped) { return; }
      const { value, done } = await s.reader.read();
      if (done) { break; }
      // Slice each incoming buffer into <=CHUNK messages and send directly.
      // No accumulation/merge (SCTP only needs a message-size CAP, not exact
      // 64 KB) and no per-chunk copy: dc.send() copies into the SCTP queue
      // synchronously, so the source buffer is free to be recycled after.
      let offset = 0;
      while (offset < value.length) {
        if (s !== session || s.stopped) { return; }
        const end = Math.min(offset + CHUNK, value.length);
        await sendChunk(s, value.subarray(offset, end));
        offset = end;
      }
    }
    if (s !== session || s.stopped) { return; }
    s.dc.send(JSON.stringify({ t: "eof", bytes: s.progress.done }));
    postProgress(s, true);
    log("eof sent, awaiting receiver ack");
    // Bound the wait for the disk-write ack so a dropped ack can't hang.
    s.ackTimer = setTimeout(() => {
      if (s === session && !s.acked) {
        fail(s, "transfer", "no delivery confirmation");
      }
    }, ACK_TIMEOUT_MS);
  } catch (e) {
    fail(s, "transfer", e);
  }
}

function sendChunk(s, view) {
  s.dc.send(view);
  s.progress.done += view.length;
  postProgress(s, false);
  if (s.dc.bufferedAmount <= BUFFER_HIGH) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    s.dc.addEventListener("bufferedamountlow", () => resolve(), { once: true });
  });
}

/* ── receiver ──────────────────────────────────────────────────────────── */

async function startReceive(msg) {
  closeSession(session);
  const s = {
    role: "receive",
    stun: msg.stun,
    stopped: false,
  };
  session = s;
  let offer;
  try {
    offer = await decodeCode(OFFER_PREFIX, msg.code);
  } catch (e) {
    // Nothing valid to hold, but SOFT: the receive screen stays on its
    // scan/paste step and the user retries with a better code.
    session = null;
    softError("offer code unreadable");
    return;
  }
  if (!offer.sdp || !(offer.size >= 0)) {
    session = null;
    softError("offer code unreadable");
    return;
  }
  s.offer = offer;
  s.progress = makeProgress(offer.size);
  post({
    type: "offer-parsed",
    name: String(offer.name || ""),
    size: offer.size,
    mime: String(offer.mime || ""),
    device: String(offer.dev || ""),
  });
}

async function acceptReceive(msg) {
  const s = session;
  if (!s || s.role !== "receive" || !s.offer) { return; }
  s.writeUrl = msg.writeUrl;
  try {
    s.pc = await newPeerConnection(s.stun);
    s.pc.ondatachannel = (ev) => { bindReceiveChannel(s, ev.channel); };
    watchConnection(s);
    await s.pc.setRemoteDescription({ type: "offer", sdp: s.offer.sdp });
    const answer = await s.pc.createAnswer();
    await s.pc.setLocalDescription(answer);
    await waitIceComplete(s.pc);
    if (s !== session || s.stopped) { return; }
    const code = await encodeCode(ANSWER_PREFIX, { v: 1, sdp: s.pc.localDescription.sdp });
    if (s !== session || s.stopped) { return; }
    post({ type: "code", role: "answer", code: code });
  } catch (e) {
    fail(s, "engine", e);
  }
}

function bindReceiveChannel(s, dc) {
  s.dc = dc;
  s.dc.binaryType = "arraybuffer";
  s.queue = [];
  s.queuedBytes = 0;
  s.written = 0;
  s.received = 0;
  s.flushing = Promise.resolve();
  s.eofBytes = -1;
  // The size the user accepted — refuse to write materially more (a modified
  // sender that advertised "2 MB" can't stream tens of GB to fill the disk).
  s.cap = (s.offer && s.offer.size >= 0) ? s.offer.size + OVERRUN_SLACK : Infinity;
  s.dc.onmessage = (ev) => {
    if (s !== session || s.stopped) { return; }
    if (typeof ev.data === "string") {
      let ctrl = null;
      try { ctrl = JSON.parse(ev.data); } catch (e) { /* ignore junk */ }
      if (ctrl && ctrl.t === "eof") {
        s.eofBytes = ctrl.bytes;
        scheduleFlush(s, true);
      }
      return;
    }
    s.received += ev.data.byteLength;
    if (s.received > s.cap) {
      fail(s, "transfer", "declared size exceeded");
      return;
    }
    s.queue.push(ev.data);
    s.queuedBytes += ev.data.byteLength;
    s.progress.done += ev.data.byteLength;
    postProgress(s, false);
    if (s.queuedBytes >= FLUSH_BYTES) {
      scheduleFlush(s, false);
    }
  };
}

// Flushes are chained on one promise so loopback POSTs stay strictly
// sequential — the write endpoint checks the offset, ordering is disk truth.
function scheduleFlush(s, isFinal) {
  const batch = s.queue;
  const batchBytes = s.queuedBytes;
  s.queue = [];
  s.queuedBytes = 0;
  s.flushing = s.flushing.then(async () => {
    if (s.stopped) { return; }
    if (batchBytes > 0) {
      const body = new Blob(batch);
      const url = s.writeUrl + "&off=" + s.written;
      const response = await fetch(url, { method: "POST", body: body });
      if (!response.ok) {
        throw new Error("loopback write " + response.status);
      }
      s.written += batchBytes;
    }
    if (isFinal) {
      if (s.eofBytes !== s.written) {
        throw new Error("short transfer: " + s.written + "/" + s.eofBytes);
      }
      // Ack AFTER the last byte hit the loopback (disk), then WAIT for the
      // ack to actually leave before tearing the transport down — pc.close()
      // aborts SCTP immediately and would otherwise drop a still-buffered ack,
      // hanging the sender.
      try {
        s.dc.send(JSON.stringify({ t: "rcvd" }));
        await waitBufferedDrain(s.dc);
      } catch (e) { /* peer gone */ }
      postProgress(s, true);
      post({ type: "done", role: "receive", bytes: s.written });
      closeSession(s);
    }
  }).catch((e) => {
    fail(s, "transfer", e);
  });
}

/* ── native port ───────────────────────────────────────────────────────── */

function post(message) {
  if (!port) { return; }
  try {
    port.postMessage(message);
  } catch (e) {
    log("post failed", e);
  }
}

function handleCommand(msg) {
  if (!msg || typeof msg.type !== "string") { return; }
  log("command:", msg.type);
  switch (msg.type) {
    case "ensure": {
      // `force` reloads unconditionally. Needed because a `typeof
      // RTCPeerConnection !== "undefined"` check is too weak: after the app
      // toggles media.peerconnection.enabled off (session end) then on again
      // (next session), the constructor still EXISTS in this un-reloaded page
      // but its ICE stack is dead — createOffer then hangs. Java forces the
      // reload whenever it had to enable the pref for the session, so the
      // page's WebRTC stack is freshly initialised with the pref on.
      const force = msg.force === true;
      const ok = !force && typeof RTCPeerConnection !== "undefined";
      post({ type: "ensure-result", ok: ok });
      if (!ok) {
        // Delay a tick so the reply flushes before the port drops.
        setTimeout(() => { location.reload(); }, 50);
      }
      break;
    }
    case "send-start":
      startSend(msg);
      break;
    case "send-answer":
      acceptAnswer(msg);
      break;
    case "recv-start":
      startReceive(msg);
      break;
    case "recv-accept":
      acceptReceive(msg);
      break;
    case "stop":
      closeSession(session);
      post({ type: "state", state: "closed" });
      break;
    default:
      log("unknown command", msg.type);
  }
}

function connectPort() {
  port = browser.runtime.connectNative(PORT_NAME);
  port.onMessage.addListener(handleCommand);
  port.onDisconnect.addListener(() => {
    log("port disconnected, reconnecting");
    closeSession(session);
    setTimeout(connectPort, 1000);
  });
  post({ type: "ready", rtc: typeof RTCPeerConnection !== "undefined" });
}

connectPort();
