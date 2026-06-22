# Firedown — Encrypted Bookmarks Sync (client implementation)

Client side of E2E-encrypted **bookmarks** sync. The **server is already live**
(`api.firedown.app`) and is a zero-knowledge encrypted-blob store. This doc is
the working spec for the Android client; it has been **reconciled to the live
server's real wire contract** (the `cloud-sync-spec-api.md` source of truth),
which differs from the early generic handoff in two important ways — see
"Reconciliation" below.

Scope: **bookmarks only, free, no email**. Identity is a client-generated
**recovery code**; the encryption key never leaves the device. Two backends, one
client: default = Firedown's hosted server, advanced users can point at their own
(same protocol). Off by default; opt-in from Settings.

Non-goals (do NOT build): media/vault sync, paid tiers, server code, premium
client gating (the app is open source — the only thing of value is the hosted
server).

---

## 0. Reconciliation — what changed vs the early handoff

The live server uses the **`cloud-sync-spec-api.md`** contract, which is the
single source of truth for *both* the Go server and this client. Two things the
generic handoff assumed are **wrong** against the live server:

1. **Auth is NOT a bearer token.** It is **Ed25519-signed requests** over a
   six-line canonical, with **explicit hashcash-PoW registration**. There is no
   `Authorization: Bearer`. See §3.
2. **Identity is the URL hash, not a random `sync_id` UUID.** The spec merges
   "last-writer-wins per **URL-hash** + tombstones" (§6.5), and the app already
   keys every bookmark on `uid = bookmarkIdFor(url) = hash(normalize(url))`
   (`WebBookmarkDataRepository`) — a deterministic, cross-device-stable URL
   identity. So **no `sync_id` UUID column is needed**; the migration only adds
   `updated_at` + tombstone columns (§4). (Trade-off: editing a bookmark's URL is
   a delete-of-old + add-of-new across devices, which matches how the app already
   treats a URL change. Accepted, per the spec.)

Everything else from the handoff stands: AES-256-GCM blob (reuse
`DownloadBackupMirror`'s AEAD), per-URL LWW + tombstone merge, WorkManager
trigger pattern, settings UI, 16-locale strings.

---

## 1. Existing code map (verified)

Bookmarks have their own Room DB (separate from downloads):

| Thing | Path |
|---|---|
| Entity | `data/entity/WebBookmarkEntity.java` |
| DAO | `data/dao/WebBookmarkDao.java` |
| Database | `data/WebBookmarkDatabase.java` (currently **version 2**) |
| Repository | `data/repository/WebBookmarkDataRepository.java` |
| Room builders | `data/di/DatabaseModule.java` |

`webbookmark` schema (v2): `uid int @PrimaryKey` (NOT autogen; `uid =
hash(normalize(url))`, insert is `REPLACE`), `file_title`, `file_url`,
`file_date` (long, creation), `file_icon`, `file_preview`.

**Reuse, don't hand-roll:**
- AEAD — `data/DownloadBackupMirror.java`: `AES/GCM/NoPadding`, framing
  `MAGIC | 12-byte IV | ciphertext+tag`, 128-bit tag, `SecureRandom` IV. Reuse
  the same primitives; only the **key** differs (from the recovery code, not
  SSAID). Extract a small shared GCM helper rather than copy-pasting.
- OkHttp — the shared client in `data/di/NetworkModule.java`. Reuse it (its
  media-oriented interceptors are inert for plain sync calls — verify).
- Worker pattern — `UpdateWorker` (`@HiltWorker` + `@AssistedInject`) +
  `UpdateScheduler` (WorkManager periodic + one-time, unique work, network
  constraints). Mirror this for the sync worker.
- Secrets — `secret_shared_prefs.xml` (excluded from Auto Backup). Store the
  recovery code wrapped via AndroidKeyStore.

---

## 2. Account & key model (recovery code → keys, client-side)

The server is **zero-knowledge about keys**: it only ever sees the `account_id`,
the registered Ed25519 pubkey, signatures, and ciphertext — it never derives or
validates the derivation. So the client owns this derivation; it is documented
here as the canonical client scheme (deterministic, so the same code on any
device yields the same identity).

- **Recovery code**: 256-bit CSPRNG random, shown to the user as a word list
  (BIP39-style, friendlier than base32). The user must save it — **no recovery
  if lost** (local bookmarks survive; degraded, not catastrophic). State this in
  the UI before enabling.
- **Derivation** (HKDF-SHA256; consistent with the spec §9 `file_key =
  HKDF(master_enc, "firedown/bookmarks/v1")`):
  ```
  PRK        = HKDF-Extract(salt = "firedown/sync/v1", ikm = recovery_code_bytes)
  account_id = HKDF-Expand(PRK, "firedown/sync/account-id/v1",   16)  // → Crockford base32 (26 chars)
  auth_seed  = HKDF-Expand(PRK, "firedown/sync/auth-ed25519/v1", 32)  // → Ed25519 keypair (auth_sk/auth_pubkey)
  master_enc = HKDF-Expand(PRK, "firedown/sync/master-enc/v1",   32)
  file_key   = HKDF(master_enc, "firedown/bookmarks/v1",         32)  // → AES-256-GCM blob key
  ```
- **Restore on a new device**: enter the recovery code → derive all → register
  (idempotent → `already-registered`) → pull → decrypt → merge into local.

---

## 3. Server wire contract (the live `cloud-sync-spec-api.md`)

- **Base URL**: `https://api.firedown.app` (+ a user-supplied URL for BYO mode).
- **Conventions**: HTTPS; unix **seconds**; random bytes on the wire are
  **base64url no-pad**; `account_id` is **Crockford base32** (26 chars);
  bookmark bodies are **raw `application/octet-stream`** (not base64-in-JSON);
  other bodies are JSON.
- **Auth (every `/v1/*` except `/v1/health` and `/v1/register/challenge`)** —
  three headers:
  ```
  X-Firedown-Account:   <account_id base32>
  X-Firedown-Timestamp: <unix seconds>
  X-Firedown-Signature: <base64url Ed25519(canonical)>
  ```
  Canonical (LF-joined, no trailing newline):
  `account_id_bytes ‖ METHOD ‖ path ‖ query ‖ ts_ascii ‖ sha256(body)`
  (account_id = the raw 16 bytes; query = raw, no leading `?`; sha256(body) =
  32 raw bytes; empty body → `e3b0c4…855`). ±300s skew window.

Endpoints used by the client:

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/health` | unauth; `{status,version,commit}` |
| `GET` | `/v1/register/challenge?account_id=<b32>` | unauth; `{challenge(b64url 32B), pow_bits, expires_at}` |
| `POST` | `/v1/account/register` | signed (body pubkey); body `{account_id, auth_pubkey, challenge, pow_nonce}`; **201** registered / **200** already-registered / **409** account-taken |
| `GET` | `/v1/sync/bookmarks` | signed; **200** octet-stream + `X-Firedown-Version` / `X-Firedown-Updated-At`, or **404** not-found (= empty, version 0) |
| `PUT` | `/v1/sync/bookmarks` | signed; header `X-Firedown-Prev-Version` (0 first push); octet-stream body; **200** `{version,updated_at}` / **409** `{server_version}` / **413** / **503** / **429** |
| `GET` | `/v1/quota` | signed; `{bytes_used, bytes_limit, tier:"free", expires_at:null}` |

- **Registration**: explicit, hashcash-PoW. `H = SHA-256("firedown/register/v1\n"
  ‖ account_id ‖ challenge ‖ nonce)` must have ≥ `pow_bits` (currently 20) leading
  zero bits. Idempotent on `(account_id, auth_pubkey)`.
- **Versioning**: monotonic int. OCC via `X-Firedown-Prev-Version`; on **409** the
  client re-pulls, re-merges, retries.
- **Limits**: `FIREDOWN_BOOKMARK_MAX_BYTES` = **512 KB** per blob (`413`); per-account
  cap 512 KB (`413 account-full`); app rate limit 120/min/account (`429
  rate-limited` + `Retry-After`). Stay well under 512 KB.
- Error envelope `{error, detail?, ray}`; switch on the **`error`** slug, never
  the HTTP status.

---

## 4. Data-model migration (additive; do first) — webbookmark v2 → v3

Add to `WebBookmarkEntity` / `webbookmark` (no `sync_id` — identity is the
URL-hash `uid`, see §0):

- `updated_at` INTEGER NOT NULL DEFAULT 0 — last-modified epoch millis (set on
  create/edit/delete). Migration backfills existing rows from `file_date`.
- `deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER NOT NULL DEFAULT 0 —
  **tombstone**. A user delete becomes `deleted=1` (so the deletion propagates);
  a GC pass hard-deletes tombstones older than a TTL (~90 days). **Every existing
  list/read query gains `WHERE deleted = 0`.** (The internal re-key path in the
  repository constructor and the GC use a *hard* delete; only user-facing deletes
  tombstone — keep both DAO methods.)

Migration rules (CLAUDE.md §"Room … persistent tracking"): additive only, real
`Migration(2,3)`, bump DB version, **bump `versionCode`**. Caveat: an orphaned
dev-only `v3` (the reverted pinned-shortcuts schema) may exist on the
maintainer's dev devices — those must clear app data (equal-version schema drift
isn't auto-healed). Real (main) users are all at v2 and migrate cleanly.

---

## 5. Blob format

- **Plaintext** = canonical JSON of the full set + tombstones + schema version,
  matching the spec §9 shape:
  ```json
  {"v":1,"updated_at":0,"bookmarks":[
     {"url":"…","title":"…","date":0,"icon":"…","preview":"…","updatedAt":0}
  ],"tombstones":[{"url":"…","deletedAt":0}]}
  ```
  Items are keyed by **normalized URL** (the merge identity). Stable key order so
  unchanged data re-encrypts deterministic-ish.
- **gzip then AES-256-GCM** with `file_key`. Blob =
  `MAGIC | schemaVersion | 12-byte IV | ciphertext+tag` — mirror
  `DownloadBackupMirror`'s framing, distinct magic `FDSB1`. (Spec §6.5: "gzip
  before AES-256-GCM".)
- **Transport**: raw `application/octet-stream` (the spec's choice — not
  base64-in-JSON). Server never sees plaintext.

---

## 6. Sync algorithm (per-URL LWW + tombstones; all merge logic client-side)

1. **Pull** `GET /v1/sync/bookmarks` (+ `X-Firedown-Version`); `404` ⇒ remote
   empty (version 0).
2. **Decrypt** → remote item set.
3. **Merge** remote ⨝ local by **normalized URL**: union of urls; per url the
   entry with greater `updatedAt`/`deletedAt` wins; a newer tombstone deletes a
   live item and vice-versa.
4. **Apply** merged result to local Room **in a transaction** (persistent-tracking
   invalidation, CLAUDE.md).
5. **Encrypt** merged set → `PUT` with `X-Firedown-Prev-Version = <pulled
   version>`.
6. On **409** (concurrent write): re-pull, re-merge, retry — bounded attempts +
   jittered backoff; honour `Retry-After` on 429/503.

- **First run**: remote `404` + local non-empty ⇒ push; remote exists + local
  empty ⇒ pull; both ⇒ merge.
- **Triggers**: bookmark add/edit/delete (debounced 5–10 s); app foreground; a
  periodic WorkManager job (network-constrained, mirror `UpdateScheduler`).
- **Offline-tolerant + idempotent**: a duplicate run is a no-op (version match).
- **Never** whole-blob last-writer-wins — always per-URL merge (else a bookmark
  added on B while A pushes is silently dropped).

---

## 7. Conventions (firedown/CLAUDE.md)

- **E2E invariant**: plaintext bookmarks + keys never leave the device.
- **Logging**: gated on `BuildConfig.DEBUG`; **never** log bookmark contents,
  URLs, titles, the recovery code, keys, or signatures. Truncate any field
  preview (`logPreview`, 128-char cap).
- **Room**: `setInMemoryTrackingMode(false)` on every DB (don't revert); additive
  migration; bump DB version + `versionCode`.
- **Hilt**: a class with `@Inject` fields must itself be `@AndroidEntryPoint`
  (workers `@HiltWorker` + `@AssistedInject`).
- **Java**: no fully-qualified names inline; match comment density (explain
  *why*). Reuse the `NetworkModule` OkHttp client.
- Don't push to `main`; feature branch only; PR when asked.

---

## 8. Task order

1. ✅ Reconcile this doc to the live contract (done).
2. Room migration v2→v3 (`updated_at`, `deleted`, `deleted_at`; gate queries on
   `deleted=0`; set `updated_at` on writes; bump DB version + `versionCode`).
3. Crypto module: Crockford, canonical, HKDF, Ed25519 (Conscrypt), PoW solver,
   gzip+AES-GCM blob (reuse `DownloadBackupMirror` AEAD). **Unit-tested + the
   shared `tests/api-vectors/*.json` interop vectors.**
4. Secure storage of the recovery code (`secret_shared_prefs` + AndroidKeyStore).
5. Sync API client over `NetworkModule` OkHttp (challenge/PoW/register, signed
   GET/PUT, prev-version OCC, 409/413/429 handling).
6. Sync engine: pull → merge(pure, **unit-tested**) → apply(txn) → push + 409
   retry; tombstone GC.
7. Worker + scheduler (mirror `UpdateWorker`/`UpdateScheduler`).
8. Settings UI: enable/disable, show recovery code (+ "no recovery" warning),
   "I have a code" restore, backend selector (hosted / custom URL), last-synced
   status, "Sync now", sign-out (wipe local keys). 16 locales.

## 9. Tests (must-have)

- **Interop vectors** — consume `tests/api-vectors/{canonical,crockford,ed25519,
  hashcash}.json` from the server repo; identical bytes both sides (the
  canonicalization drift early-warning).
- **Merge unit tests** (pure): A-adds/B-adds union; conflicting edits (newer
  `updatedAt` wins); delete-vs-edit both directions; tombstone TTL/GC; idempotent
  re-merge.
- **Crypto round-trip**: encrypt→decrypt equality; wrong key fails AEAD; framing
  parse; PoW solve/verify.
- **409 flow**: simulated concurrent write → re-pull/re-merge succeeds in budget.
- **Migration test**: v2 rows get `updated_at` backfilled and survive; list
  queries hide tombstones.
