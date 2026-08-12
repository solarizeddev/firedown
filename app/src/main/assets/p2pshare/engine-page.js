/* Firedown P2P share engine — WebRTC DataChannel transfer, driven by Java.
 *
 * WHERE THIS RUNS (load-bearing): this is a PAGE-WORLD script, loaded as a
 * same-origin <script> of the loopback page http://127.0.0.1:<port>/engine
 * inside a HIDDEN GeckoSession created by P2pShareController. It does NOT run
 * in the extension background page — WebRTC's createOffer() HANGS FOREVER
 * there because a background page has no real browsing context (docShell).
 * A real content document (this page, exactly like a normal tab) is the only
 * context where GeckoView's WebRTC actually completes. Proven on-device: a
 * datachannel sample works in a tab but not in the background page. Don't
 * move this back into a background page or a content-script sandbox.
 *
 * Because it's a page script it has NO browser.runtime — it talks to Java via
 * window.postMessage to the bridge CONTENT SCRIPT (content.js) that shares
 * this document, which relays to/from the native "p2pshare" port. Transport:
 * events out = window.postMessage({p2p:"evt", data}); commands in = a window
 * "message" of {p2p:"cmd", data}. The bridge sends {type:"__init", debug}
 * once its port is up; we then post the first {type:"ready"}.
 *
 * The rest (loopback byte bridge, offline QR/share-sheet signaling, non-trickle
 * ICE) is unchanged:
 * - File bytes NEVER cross messaging: the SENDER fetch()es the file from the
 *   same loopback (GET /read) and pumps it into the DataChannel; the RECEIVER
 *   POSTs chunks back (/write), which Java writes to disk.
 * - Signaling is OFFLINE: offer/answer are FDS1./FDR1. codes shown as QR or
 *   sent via the share sheet. The only external party is the user's STUN
 *   server, and none at all on a shared LAN.
 *
 * Protocol (Java -> cmd):
 *   {type:"__init", debug}                             bridge handshake
 *   {type:"send-start", readUrl, name, size, mime, device, iceServers[],
 *       answerUrl?, rendezvousUrl?}
 *   {type:"send-answer", code} · {type:"recv-start", code, iceServers[]}
 *   {type:"recv-accept", writeUrl, resumeOff?, resumeTail?} · {type:"stop"}
 * (evt -> Java):
 *   {type:"ready", rtc} · {type:"code", role, code} · {type:"offer-parsed", …}
 *   {type:"state", state} · {type:"progress", …} · {type:"done", role, bytes}
 *   {type:"error", code, detail}   ("bad-code" is soft — session survives)
 *
 * RESUME (all fields optional — every old<->new pairing keeps working):
 *   The offer carries res:1 (this sender can serve ranged reads and verify a
 *   tail). A receiver holding a matching .part answers with off:<bytes on
 *   disk> + tail:<base64url SHA-256 of the part's last 64 KB> — computed by
 *   Java, which owns the file. The sender hashes ITS OWN bytes at the same
 *   range (ranged loopback read + crypto.subtle) and opens the DataChannel
 *   conversation with {"t":"begin","off":X}: X = off when the tails match
 *   (same file — stream the remainder), 0 when they don't (different file
 *   behind the same name — start over). "begin" is sent ONLY when the answer
 *   requested a resume, so an old receiver never sees an unknown control
 *   message; an old sender never sees off/tail it would ignore, because the
 *   receiver only requests when the offer carried res. The ordered channel
 *   guarantees begin precedes every chunk.
 */

"use strict";

// Resolved from the bridge's __init handshake (BuildConfig.DEBUG). Release
// builds log nothing — logging discipline.
let DEBUG = false;

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
const CONNECT_TIMEOUT_MS = 30000;
const GATHER_TIMEOUT_MS = 5000;
// When TURN is configured, hold gathering longer than the soft cap: behind a
// full-tunnel VPN the only viable pair is relay<->relay, and the relay candidate
// often arrives late because TURN falls back to TURN-over-TCP (3478->443), which
// routinely takes >5s through the tunnel. Bailing at the soft cap mints an SDP
// with NO relay candidate, so the peer can never form the relay pair -> no-path.
const RELAY_GATHER_TIMEOUT_MS = 15000;
// A lossy relay/VPN path flaps ICE consent freshness constantly and usually
// recovers within ~2s; debounce "disconnected" so the reconnecting spinner
// doesn't strobe on every blip.
const RECONNECT_DEBOUNCE_MS = 2000;
const PROGRESS_INTERVAL_MS = 400;
const ACK_TIMEOUT_MS = 30000;
const DRAIN_TIMEOUT_MS = 5000;
const OVERRUN_SLACK = 1024 * 1024;

// How much of the partial's tail the resume handshake hashes. Enough that a
// coincidental match across different files is not a real-world event, small
// enough that both sides hash it in one cheap read.
const RESUME_TAIL = 64 * 1024;

const OFFER_PREFIX = "FDS1.";
const ANSWER_PREFIX = "FDR1.";

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

// The ICE server list comes fully-formed from Java (STUN + default/opt-in TURN,
// see the controller's putIceServers) in RTCIceServer shape. Just carry it.
function iceOf(msg) {
  return Array.isArray(msg.iceServers) ? msg.iceServers : [];
}

// Keep only well-formed stun:/turn: entries so one bad settings value can't
// throw in the RTCPeerConnection constructor and fail the whole share.
function sanitizeIceServers(list) {
  const clean = [];
  for (let i = 0; i < list.length; i++) {
    const s = list[i];
    if (!s || s.urls == null) { continue; }
    const urls = Array.isArray(s.urls)
      ? s.urls.filter((u) => typeof u === "string" && /^stuns?:|^turns?:/i.test(u))
      : (/^stuns?:|^turns?:/i.test(s.urls) ? [s.urls] : []);
    if (urls.length === 0) { continue; }
    const entry = { urls: urls };
    if (typeof s.username === "string" && s.username) { entry.username = s.username; }
    if (typeof s.credential === "string" && s.credential) { entry.credential = s.credential; }
    clean.push(entry);
  }
  return clean;
}

async function newPeerConnection(ice) {
  // ice is the RTCIceServer[] Java built from the user's own settings (STUN
  // echo + any user-configured TURN). Sanitized so a bad entry can't break
  // the ctor.
  const config = {};
  const servers = sanitizeIceServers(ice || []);
  if (servers.length > 0) {
    config.iceServers = servers;
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
  const pc = new RTCPeerConnection(config);
  // Diagnostic (DEBUG only): the candidate mix tells the failure story —
  // no host candidates = interface problem, .local host candidates = mDNS
  // obfuscation still on, no srflx = STUN unreachable.
  pc.addEventListener("icecandidate", (ev) => {
    if (ev.candidate && ev.candidate.candidate) {
      log("candidate:", ev.candidate.candidate);
    }
  });
  return pc;
}

// True when the ICE config carries a TURN server — i.e. a relay candidate is
// expected and (for a full-tunnel-VPN pair) REQUIRED in the minted SDP.
function wantsRelay(servers) {
  for (let i = 0; i < servers.length; i++) {
    const urls = servers[i] && servers[i].urls;
    const list = Array.isArray(urls) ? urls : [urls];
    for (let j = 0; j < list.length; j++) {
      if (typeof list[j] === "string" && list[j].indexOf("turn:") === 0) {
        return true;
      }
    }
  }
  return false;
}

// Non-trickle: resolve once gathering completes so the emitted code carries the
// full candidate set. The soft cap (GATHER_TIMEOUT_MS) returns whatever gathered
// so far — host candidates alone still connect same-LAN pairs even if STUN is
// unreachable. But when TURN is configured we must NOT mint an SDP without a
// relay candidate (it's the only viable pair behind a VPN — see
// RELAY_GATHER_TIMEOUT_MS): at the soft cap, if we still need a relay candidate
// and haven't gathered one, keep waiting for it (or the hard cap) instead of
// bailing with a relay-less set.
function waitIceComplete(pc, wantRelay) {
  return new Promise((resolve) => {
    if (pc.iceGatheringState === "complete") {
      resolve();
      return;
    }
    let sawRelay = false;
    let softElapsed = false;
    let finished = false;
    const finish = (reason) => {
      if (finished) { return; }
      finished = true;
      clearTimeout(softTimer);
      clearTimeout(hardTimer);
      log("ice gathering done:", reason);
      resolve();
    };
    pc.addEventListener("icecandidate", (ev) => {
      const cand = ev.candidate && ev.candidate.candidate;
      if (cand && cand.indexOf(" typ relay") !== -1) {
        sawRelay = true;
        // The relay candidate we were holding for arrived after the soft cap.
        if (softElapsed) { finish("relay candidate"); }
      }
    });
    pc.addEventListener("icegatheringstatechange", () => {
      if (pc.iceGatheringState === "complete") { finish("complete"); }
    });
    const softTimer = setTimeout(() => {
      softElapsed = true;
      // Host/srflx have had long enough. Resolve now UNLESS we still owe a relay
      // candidate — then hold for it (onicecandidate above) or the hard cap.
      if (!wantRelay || sawRelay) {
        finish("soft cap");
      } else {
        log("soft cap reached, holding for relay candidate");
      }
    }, GATHER_TIMEOUT_MS);
    const hardTimer = setTimeout(() => finish("hard cap"),
        wantRelay ? RELAY_GATHER_TIMEOUT_MS : GATHER_TIMEOUT_MS);
  });
}

function watchConnection(s) {
  const pc = s.pc;
  // Do NOT arm the no-path timer here. Signaling is OFFLINE and human-paced:
  // between showing the offer QR and the answer being applied, the receiver
  // has to scan, preview, accept, and relay a reply QR back — easily longer
  // than CONNECT_TIMEOUT_MS. Arming at offer-creation made the sender fail with
  // "no-path" while it was merely WAITING to be scanned. The timer must bound
  // only the ACTUAL ICE connectivity phase, which begins when connectionState
  // first reaches "connecting" (both SDPs exchanged, checks underway) — so arm
  // it there, once, and never during the wait-for-scan phase.
  pc.addEventListener("connectionstatechange", () => {
    log("connectionState:", pc.connectionState);
    if (s !== session) { return; }
    if (pc.connectionState === "connected") {
      clearTimeout(s.connectTimer);
      s.connectTimer = null;
      clearTimeout(s.disconnectTimer);
      s.disconnectTimer = null;
      post({ type: "state", state: "connected" });
      reportTransport(s, pc);
    } else if (pc.connectionState === "failed") {
      fail(s, "no-path", "connection failed");
    } else if (pc.connectionState === "disconnected") {
      // Transient (NOT terminal — "failed" is): ICE consent checks are missing
      // on a lossy path (common when a peer is on a slow/full-tunnel VPN), and
      // it usually recovers to "connected". A relayed VPN path flaps this
      // constantly, so DEBOUNCE — only surface "reconnecting" if it persists
      // past RECONNECT_DEBOUNCE_MS, else the spinner strobes on every blip.
      if (!s.disconnectTimer) {
        s.disconnectTimer = setTimeout(() => {
          s.disconnectTimer = null;
          if (s === session && s.pc.connectionState === "disconnected") {
            post({ type: "state", state: "disconnected" });
          }
        }, RECONNECT_DEBOUNCE_MS);
      }
    } else if (pc.connectionState === "connecting") {
      post({ type: "state", state: "connecting" });
      if (!s.connectTimer) {
        s.connectTimer = setTimeout(() => {
          if (s === session && pc.connectionState !== "connected") {
            fail(s, "no-path", "connect timeout");
          }
        }, CONNECT_TIMEOUT_MS);
      }
    }
  });
}

// After connecting, tell Java whether the live path RELAYS through the TURN
// server or is DIRECT (peer-to-peer) — so the UI can be honest about whether
// the file touches a server (it does, encrypted, when relayed). Reads the
// selected ICE candidate pair from getStats; best-effort, so any hiccup just
// leaves the optimistic default. Re-runs on every "connected" (incl. a
// reconnect that may switch direct↔relay).
function reportTransport(s, pc) {
  if (!pc.getStats) { return; }
  pc.getStats().then((stats) => {
    if (s !== session) { return; }
    let selectedPairId = null;
    stats.forEach((r) => {
      if (r.type === "transport" && r.selectedCandidatePairId) {
        selectedPairId = r.selectedCandidatePairId;
      }
    });
    let found = false;
    let relayed = false;
    stats.forEach((r) => {
      if (r.type !== "candidate-pair") { return; }
      // Prefer the transport's explicit pointer; fall back to Firefox's
      // `selected` flag or the nominated+succeeded pair.
      const isSelected = selectedPairId
        ? r.id === selectedPairId
        : (r.selected || (r.nominated && r.state === "succeeded"));
      if (!isSelected) { return; }
      found = true;
      const local = stats.get(r.localCandidateId);
      const remote = stats.get(r.remoteCandidateId);
      // Diagnostic (DEBUG only): which pair actually won, e.g.
      // "host <-> host" (same LAN), "srflx <-> srflx" (NAT hairpin — STUN
      // discovered the address but the bytes still go peer-to-peer), or
      // anything with "relay" (through the TURN server). The `relayed`
      // boolean below can't distinguish the first two, and that is the
      // question every "did this touch a server?" report starts with.
      log("selected pair:",
          (local && local.candidateType) || "?",
          "<->",
          (remote && remote.candidateType) || "?");
      if ((local && local.candidateType === "relay") ||
          (remote && remote.candidateType === "relay")) {
        relayed = true;
      }
    });
    if (found) {
      post({ type: "transport", relayed: relayed });
    } else {
      // No pair identified — the footer stays on the path-neutral copy
      // rather than claiming a direct transfer we haven't verified.
      log("selected pair: unknown (no candidate-pair matched)");
    }
  }).catch((e) => { log("transport probe failed:", e); });
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
  clearTimeout(s.disconnectTimer);
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
    const iceServers = iceOf(msg);
    s.pc = await newPeerConnection(iceServers);
    s.wantRelay = wantsRelay(iceServers);
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
    await waitIceComplete(s.pc, s.wantRelay);
    log("ICE gathering done");
    if (s !== session || s.stopped) { return; }

    // The code carries the metadata too — the receiver previews name/size
    // BEFORE anything connects, and the accept happens offline.
    // res:1 = this sender can resume (ranged loopback reads + tail verify);
    // a receiver holding a matching .part answers with off/tail.
    const payload = {
      v: 1,
      res: 1,
      sdp: s.pc.localDescription.sdp,
      name: msg.name,
      size: msg.size,
      mime: msg.mime,
      dev: msg.device,
    };
    // Answer-return URLs. Two, tried by the receiver in order; both are
    // optional and the human-relayed reply link/QR remains the last resort:
    //  - ans: the sender's LAN listener (same network, never leaves it).
    //  - rvz: the one-time public rendezvous (api.firedown.app) the sender
    //    long-polls — what makes a cross-network share complete with NO
    //    reply step at all.
    if (msg.answerUrl) {
      payload.ans = msg.answerUrl;
    }
    if (msg.rendezvousUrl) {
      payload.rvz = msg.rendezvousUrl;
    }
    const code = await encodeCode(OFFER_PREFIX, payload);
    if (s !== session || s.stopped) { return; }
    post({ type: "code", role: "offer", code: code });
  } catch (e) {
    fail(s, "engine", e);
  }
}

// The resume offset an answer payload requests, validated: a positive finite
// byte count no larger than the file, with a tail hash to check it against.
// Anything else (old receiver, junk) is 0 = no resume requested.
function requestedResumeOf(payload, size) {
  if (!payload || !Number.isFinite(payload.off)) { return 0; }
  if (!(payload.off > 0) || payload.off > size) { return 0; }
  if (typeof payload.tail !== "string" || payload.tail.length === 0) { return 0; }
  return payload.off;
}

// Sender-side resume verification: hash OUR bytes at [off - tailLen, off) and
// compare against the receiver's tail hash. A mismatch means the .part behind
// that name is from a DIFFERENT file (same name + size is not proof) — resume
// would splice two files together, so the caller starts from 0 instead.
// Any failure (loopback hiccup, no crypto.subtle) degrades to "no resume".
async function verifyResumeTail(readUrl, off, tailB64) {
  if (typeof crypto === "undefined" || !crypto.subtle) { return false; }
  const len = Math.min(RESUME_TAIL, off);
  const response = await fetch(readUrl + "&from=" + (off - len) + "&len=" + len);
  if (!response.ok) { return false; }
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length !== len) { return false; }
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return bytesToBase64Url(digest) === tailB64;
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
  // The resume verify below awaits a fetch, which widens the window where the
  // LAN/rendezvous happy-eyeballs race could deliver a SECOND answer while
  // the first is still pre-setRemoteDescription. First one wins; reset on the
  // soft-error paths so a bad paste doesn't block a good re-paste.
  if (s.answering) {
    log("ignoring concurrent answer");
    return;
  }
  s.answering = true;
  let payload;
  const raw = String(msg.code || "");
  log("send-answer code len=" + raw.length + " head=" + raw.slice(0, 14));
  try {
    payload = await decodeCode(ANSWER_PREFIX, msg.code);
  } catch (e) {
    // Any unreadable code is soft — the offer QR is still valid, re-scan.
    log("answer decode threw: " + (e && e.message) + " (len=" + raw.length
        + " head=" + raw.slice(0, 14) + ")");
    s.answering = false;
    softError("answer code unreadable");
    return;
  }
  if (s !== session || s.stopped) { return; }
  // Resume request: decide the begin offset BEFORE setRemoteDescription — the
  // datachannel can open (and pumpFile run) any time after SRD, and the
  // decision must already be made by then.
  const requested = requestedResumeOf(payload, s.size);
  s.sendBegin = requested > 0;
  s.beginAt = 0;
  if (requested > 0) {
    let match = false;
    try {
      match = await verifyResumeTail(s.readUrl, requested, payload.tail);
    } catch (e) {
      log("resume verify failed", e);
    }
    if (s !== session || s.stopped) { return; }
    s.beginAt = match ? requested : 0;
    log("resume requested at", requested, match ? "(verified)" : "(tail mismatch, restarting)");
  }
  try {
    await s.pc.setRemoteDescription({ type: "answer", sdp: payload.sdp });
    post({ type: "state", state: "connecting" });
  } catch (e) {
    // A decodable-but-unapplicable answer (mismatched/corrupt SDP) before we
    // ever connect is still recoverable: the offer stands, treat as soft.
    s.answering = false;
    softError("answer not applicable");
  }
}

async function pumpFile(s) {
  const startAt = s.beginAt || 0;
  log("datachannel open, pumping", s.size - startAt, "of", s.size, "bytes");
  try {
    // Tell the receiver where the stream lands BEFORE any chunk (ordered
    // channel = guaranteed first). Only when the answer requested a resume —
    // an old receiver must never see an unknown control message.
    if (s.sendBegin) {
      s.dc.send(JSON.stringify({ t: "begin", off: startAt }));
    }
    // Progress counts from the resume point: done/total mirror the file, so
    // the bar starts where the previous attempt left off. lastBytes too, or
    // the first rate sample would count the resumed bytes as instant.
    s.progress.done = startAt;
    s.progress.lastBytes = startAt;
    const url = startAt > 0 ? s.readUrl + "&from=" + startAt : s.readUrl;
    const response = await fetch(url);
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
    // Held until acceptReceive builds the peer connection (the receiver only
    // connects on Accept, after the offer preview).
    ice: iceOf(msg),
    stopped: false,
  };
  session = s;
  let offer;
  const raw = String(msg.code || "");
  log("recv-start code len=" + raw.length + " head=" + raw.slice(0, 14));
  try {
    offer = await decodeCode(OFFER_PREFIX, msg.code);
  } catch (e) {
    // Nothing valid to hold, but SOFT: the receive screen stays on its
    // scan/paste step and the user retries with a better code.
    log("offer decode threw: " + (e && e.message) + " (len=" + raw.length
        + " head=" + raw.slice(0, 14) + ")");
    session = null;
    softError("offer code unreadable");
    return;
  }
  if (!offer.sdp || !(offer.size >= 0)) {
    log("offer missing fields: sdp=" + (!!offer.sdp) + " size=" + offer.size);
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
    // Sender's answer-return URLs — Java POSTs the answer to the LAN one,
    // then the rendezvous; both empty = human-relayed reply only.
    ans: String(offer.ans || ""),
    rvz: String(offer.rvz || ""),
  });
}

// The resume request the answer should carry, or null. Gated on the OFFER's
// res flag: an old sender neither serves ranged reads nor sends "begin", so
// requesting a resume from it would leave the receiver waiting for a begin
// that never comes while chunks land at the wrong offset.
function resumeAnswerFields(offer, resumeOff, resumeTail) {
  if (!offer || offer.res !== 1) { return null; }
  if (!Number.isFinite(resumeOff) || !(resumeOff > 0)) { return null; }
  if (typeof resumeTail !== "string" || resumeTail.length === 0) { return null; }
  return { off: resumeOff, tail: resumeTail };
}

async function acceptReceive(msg) {
  const s = session;
  if (!s || s.role !== "receive" || !s.offer) { return; }
  s.writeUrl = msg.writeUrl;
  // resumeOff/resumeTail come from Java, which owns the .part (it measured
  // the bytes on disk and hashed the tail while arming the write target).
  const resume = resumeAnswerFields(s.offer, msg.resumeOff, msg.resumeTail);
  s.resumeOff = resume ? resume.off : 0;
  try {
    s.pc = await newPeerConnection(s.ice);
    s.wantRelay = wantsRelay(s.ice);
    s.pc.ondatachannel = (ev) => { bindReceiveChannel(s, ev.channel); };
    watchConnection(s);
    await s.pc.setRemoteDescription({ type: "offer", sdp: s.offer.sdp });
    const answer = await s.pc.createAnswer();
    await s.pc.setLocalDescription(answer);
    await waitIceComplete(s.pc, s.wantRelay);
    if (s !== session || s.stopped) { return; }
    const payload = { v: 1, sdp: s.pc.localDescription.sdp };
    if (resume) {
      payload.off = resume.off;
      payload.tail = resume.tail;
    }
    const code = await encodeCode(ANSWER_PREFIX, payload);
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
  s.flushing = Promise.resolve();
  s.eofBytes = -1;
  // Resume: when we requested one (off/tail in the answer), NOTHING may land
  // before the sender's "begin" says where the stream starts — the verified
  // resume offset, or 0 when the sender's tail didn't match (its first POST at
  // off=0 makes the loopback truncate the armed .part, see handleWrite).
  // Without a request the stream starts at 0, begin never comes.
  s.begun = !(s.resumeOff > 0);
  // The size the user accepted — refuse to write materially more (a modified
  // sender that advertised "2 MB" can't stream tens of GB to fill the disk).
  // Enforced on the on-disk total (resume point + streamed), progress.done.
  s.cap = (s.offer && s.offer.size >= 0) ? s.offer.size + OVERRUN_SLACK : Infinity;
  s.dc.onmessage = (ev) => {
    if (s !== session || s.stopped) { return; }
    if (typeof ev.data === "string") {
      let ctrl = null;
      try { ctrl = JSON.parse(ev.data); } catch (e) { /* ignore junk */ }
      if (ctrl && ctrl.t === "begin") {
        const off = (Number.isFinite(ctrl.off) && ctrl.off > 0) ? ctrl.off : 0;
        s.written = off;
        s.begun = true;
        s.progress.done = off;
        s.progress.lastBytes = off;
        postProgress(s, true);
        log("stream begins at", off);
      } else if (ctrl && ctrl.t === "eof") {
        s.eofBytes = ctrl.bytes;
        scheduleFlush(s, true);
      }
      return;
    }
    if (!s.begun) {
      // We asked to resume and the sender streamed without saying where the
      // bytes land — offsets would be guesses, and a wrong guess corrupts the
      // file. Old senders can't reach here (they are never asked to resume).
      fail(s, "transfer", "data before begin");
      return;
    }
    s.progress.done += ev.data.byteLength;
    if (s.progress.done > s.cap) {
      fail(s, "transfer", "declared size exceeded");
      return;
    }
    s.queue.push(ev.data);
    s.queuedBytes += ev.data.byteLength;
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

/* ── transport: window.postMessage to the bridge content script ──────────── */

// Post an event out to the bridge (content.js), which relays it to the native
// port and on to Java. Same-origin target — this page and the bridge share
// the loopback origin.
function post(message) {
  try {
    window.postMessage({ p2p: "evt", data: message }, location.origin);
  } catch (e) {
    log("post failed", e);
  }
}

function handleCommand(msg) {
  if (!msg || typeof msg.type !== "string") { return; }
  log("command:", msg.type);
  switch (msg.type) {
    case "__init":
      // Bridge handshake: it has connected the native port and resolved the
      // debug flag. Apply it, then announce readiness. In this REAL page
      // context RTCPeerConnection is always present and functional (WebRTC
      // works exactly as in a normal tab), so rtc is always true — the old
      // pref-gated-global reload dance is gone.
      DEBUG = msg.debug === true;
      post({ type: "ready", rtc: typeof RTCPeerConnection !== "undefined" });
      break;
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

window.addEventListener("message", (ev) => {
  // Only our own bridge's command messages (same window, same origin).
  if (ev.source !== window || !ev.data || ev.data.p2p !== "cmd") { return; }
  handleCommand(ev.data.data);
});

// Tell the bridge we're loaded and listening; it replies with __init.
window.postMessage({ p2p: "hello" }, location.origin);
