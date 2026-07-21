# Firedown signaling relay

The **only** server in "Send directly", and deliberately tiny. It brokers the
WebRTC handshake so a shared link works one-way — sender shares a link, receiver
taps it, done — even across different networks, **without ever seeing a byte of
the file**. The file streams peer-to-peer and end-to-end encrypted (DTLS); this
service only holds two ~1 KB text blobs (the offer and the answer) for at most a
few minutes, then forgets them.

It exists because WebRTC always needs the receiver's *answer* to get back to the
sender, and a phone behind NAT has no address the answer can be sent to directly.
On the **same Wi-Fi** the app returns the answer over the LAN and never contacts
this relay at all — the relay is only the cross-network path.

## What it is / isn't

- **Is:** a blind rendezvous for two ~1 KB handshake blobs, single-use, TTL ≤ 3 min.
- **Isn't:** a file server, a TURN relay, or a store. It never sees file bytes,
  never stores anything to disk, logs no blobs, sets no cookies.

## Run it

Zero dependencies — just Node ≥ 18.

```sh
node server.js            # binds 127.0.0.1:8787 by default
```

Put TLS in front (nginx / Caddy / Cloudflare) on e.g. `sig.firedown.app`.
Example Caddy:

```
sig.firedown.app {
    reverse_proxy 127.0.0.1:8787
}
```

Environment overrides (all optional):

| var | default | meaning |
|-----|---------|---------|
| `PORT` / `HOST` | `8787` / `127.0.0.1` | bind address |
| `FDS_ORIGIN` | derived from Host header | public origin, e.g. `https://sig.firedown.app` |
| `FDS_TTL_MS` | `180000` | session lifetime |
| `FDS_LONGPOLL_MS` | `25000` | how long a sender's answer-poll parks |
| `FDS_MAX_BLOB` | `16384` | per-blob size cap (bytes) |
| `FDS_MAX_SESSIONS` | `20000` | in-flight session cap |
| `FDS_STORE_URL` | `https://firedown.app` | "Get Firedown" link on the landing page |
| `FDS_APP_ID` | `com.solarized.firedown` | App Link package |
| `FDS_APP_FINGERPRINTS` | — | comma-separated SHA-256 signing-cert fingerprints for App Links |

## Protocol (FDS-SIG/1)

`<id>` is a 128-bit base64url token the **sender** mints (the relay never issues
ids — it can't correlate shares it wasn't given). Everything is first-writer-wins
and single-use.

| method | path | who | body | notes |
|--------|------|-----|------|-------|
| POST | `/o/<id>` | sender | offer (`FDS1.…`) | upload the offer |
| GET  | `/o/<id>` | receiver | — | fetch the offer (404 until uploaded / after TTL) |
| POST | `/a/<id>` | receiver | answer (`FDR1.…`) | 404 if no offer, 409 if already answered |
| GET  | `/a/<id>?wait=1` | sender | — | long-poll; 200 with answer, or 204 on timeout (re-poll) |
| GET  | `/s/<id>` | anyone | — | landing page + App Link target |
| GET  | `/.well-known/assetlinks.json` | Android | — | App Link verification |
| GET  | `/healthz` | — | — | liveness |

The sender reading the answer (`GET /a`) drops the session immediately.

## The shared link

The app shares `https://<origin>/s/<id>`. Tapped in any chat app it either:

- opens **Firedown** straight into the receive preview (Android App Link), or
- shows a small **landing page** ("Open in Firedown" / "Get Firedown") if the
  app isn't installed.

### App Links (so the link opens the app, not the browser)

1. Fill `FDS_APP_FINGERPRINTS` with your release signing cert's SHA-256:
   ```sh
   keytool -list -v -keystore my-release.jks -alias my-alias | grep SHA256
   ```
   (Use the **Play App Signing** cert fingerprint if you distribute via Play.)
2. The relay then serves a valid `/.well-known/assetlinks.json`.
3. The app declares the `https://<origin>/s/*` intent-filter with
   `android:autoVerify="true"` (already wired in `AndroidManifest.xml`; set the
   host to your `<origin>`).

Until App Links verify, the link still works — it just shows the landing page,
whose "Open in Firedown" button uses the `firedown://p2p/r/<id>?s=<origin>`
custom scheme as a fallback.

## Privacy notes

- No blob is ever written to disk or logged. Restarting the process forgets all
  in-flight sessions (a share mid-handshake just falls back to the reply code).
- The relay *can* see the offer/answer SDP + the filename/size carried in the
  offer for the few seconds it holds them. If you want it fully blind, a future
  version can AES-GCM the offer with a key carried in the link fragment (never
  sent to the server); the relay would then store only ciphertext.
- Rate-limit / firewall at your TLS layer as usual; the built-in caps
  (`FDS_MAX_SESSIONS`, per-blob size) are a floor, not a substitute.
