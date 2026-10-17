# Server sunset — the plan, step by step

Firedown stops running a paid cloud. The hosted Cloud Backup
(`storage.firedown.app`), the payments mint (`mint.firedown.app`) and the
browser client at `firedown.app/backup/` close; the "cloud" VPS that holds
them is destroyed, and probably the TURN relay's box with it. What survives
on a Firedown server afterwards holds no user data: the website, the update
ping it serves, the crash collector, feedback, bookmark sync and the P2P
mailbox — `firedown-api`, moved onto the www host under its existing name.

The app side of this ships in ONE release: the hosted-service code is
deleted and **Backup writes to a folder the user picks** instead — Google
Drive, Dropbox, Nextcloud, an SD card, the phone itself, anything with a
`DocumentsProvider` — in the same encrypted format, under the same recovery
code. No read-only phase, no delete date in the app, no migration tool.

This file is the checklist. Tick boxes as steps land; each phase names its
repo, its gate, and the verification that closes it. Decisions are recorded
here so they are not re-derived.

## Decisions (2026-10-12)

- **One app release, not two.** An earlier cut of this plan shipped a
  read-only phase first (every write door off, a stated deletion date, a
  later release deleting the code). It was dropped (maintainer: "the delete
  date is irrelevant"): the hosted service had a handful of accounts holding
  files, and a release that both removes the service and ships its
  replacement serves them better than weeks of a half-dead screen. The
  `CloudSunset` switch, its strings and its date are gone from the tree.
- **The folder backup replaces the service, it does not migrate from it.**
  Nothing copies objects out of `storage.firedown.app`: a user who wants
  their hosted files keeps them by restoring to the phone BEFORE updating
  (the old build's Backups list still restores until Phase 3 pulls the
  server), then backs them up again into a folder. The folder format is the
  hosted one (FDVC2 chunks, the sidecar under the bookmark-blob framing), so
  the crypto, its tests and the shared vectors carried over unchanged.
- **`api.firedown.app` keeps its NAME and moves HOST.** Every APK in the wild
  posts crashes, feedback and P2P signaling to that name
  (`Preferences.SYNC_DEFAULT_BACKEND`), and the release build PUTs its R8
  mapping there. A rename would cut off every install that never updates.
  Crash and feedback move TOGETHER with bookmark sync, as the whole
  `firedown-api` binary: two tables in its one Postgres database plus the
  mappings directory. Slimming the binary is a later pass.
- **Stats never move.** DAU/MAU come from the www nginx log of `/status.json`
  (`firedown-website/ops/stats`), not from the API.
- **The TURN relay is independent of the cloud VPS** (`turn.firedown.app`
  is its own box) and the client already degrades to direct + STUN when
  `/v1/relay/creds` answers 404. Dropping it needs no app change: delete
  `FIREDOWN_TURN_SECRET` from `api.env`, destroy the box.
- **Stop selling the day the release goes out** — server side, so the old
  APKs still in the field stop too: deactivate every mint keyset. The new
  APK has no buy screen to show the refusal; the old one shows its existing
  "No storage credit is available to buy right now".
- **Old APKs get `410 Gone`, not a connection refused.** For as long as the
  www host serves anything, `storage.firedown.app` and `mint.firedown.app`
  answer every request 410 (a one-line nginx server block, no backend). The
  old client treats a 4xx as fatal, which is the honest outcome: its Cloud
  screen reports an error instead of hanging on a DNS miss.

## Today's hosts

| host | what runs there | fate |
|---|---|---|
| **www** (nginx, Let's Encrypt, Cloudflare-fronted) | static site, `status.json`, `ops/stats` timers | stays; GAINS `firedown-api` + Postgres (Phase 2) |
| **cloud VPS** (OVH; Caddy, Postgres) | `storage-api` + R2, `mint-api` + phoenixd + pruned bitcoind, `firedown-api` (bookmarks, crash, feedback, P2P, relay creds) | loses `firedown-api` (Phase 2), destroyed (Phase 3) |
| **turn** (coturn) | the TURN relay | stays, on its own box (decided 2026-10-16) |

## Phase 1 — release N: the hosted service deleted, the folder backup shipped (`firedown`)

Branch `ccr-4d141886-ro0eii`. What the release carries, each a box:

- [x] **1.1 Delete the hosted service's code.** `StorageApiClient`,
      `CloudWatchWorker`, the whole payments stack (`MintClient`,
      `BlindSignature`, `CreditPurchase`, `CreditSettlement`,
      `CreditSettleWorker`, `PendingPurchase`, `BuyCredit*`, the bitcoin
      help sheet, their layouts, drawables and JVM tests), browser pairing
      (`PairSeal`, `PairClient`, `PairApprovalViewModel`, the approval
      dialog, `PairSealTest`), the hosted live tests (`CloudBackupLiveTest`,
      `CreditPurchaseLiveTest`, `VaultSmokeTest`), the read-only switch
      (`CloudSunset`) and 160 strings across the 16 locales. The home's
      cloud chip/card and the restore-all deep link went with them.
- [x] **1.2 `FolderStore`** — the SAF tree (persisted read+write grant),
      `<picked>/Firedown Backup/<account>/objects/<id>.fdvc` + `<id>.entry`
      (the encrypted sidecar, written LAST as the commit marker), orphan
      sweep, provider-conflict-copy tolerance, `DocumentsContract` not
      `DocumentFile`, `UnreachableException` as the one named failure.
- [x] **1.3 `VaultEngine` over the folder** — same chunk format, same
      dedup by name+size, sidecar backfill of thumb/origin; backup and
      restore workers unchanged in shape, no network constraint (the
      provider's own app syncs).
- [x] **1.4 `CloudBackupManager` as the folder facade** — `isSetUp` = code
      AND folder, `adoptFolder`, `providerLabel`, status = two local facts
      (reachable, contents) cached in memory and mirrored to prefs for the
      lost state, `cancelLegacyWatch` at boot.
- [x] **1.5 The UI per the canvas design** — the Backup screen (hero in
      three states: set up / folder lost / the roadmap), the morphing CTA
      (Create recovery code → Choose backup folder → Change folder /
      Choose the folder again), the Backups list header naming the
      provider, the Storage screen's offload confirm naming the provider,
      the folder-lost notification, the one-stage Downloads banner, the
      download sheet's "Back up to <provider>" row. Strings in all 16
      locales; 24 new keys, 40 reworded.
- [x] **1.6 Docs** — `SECURITY.md`, this plan, `CLAUDE.md`'s Backup
      section, the CHANGELOG entry.
- [ ] **1.7 Build + device pass (maintainer).** The sandbox cannot compile
      the app: `./gradlew assembleDebug`, `testDebugUnitTest`
      (`VaultCryptoTest` pins the format), then on a phone: pick a Drive
      folder, back up, kill the app, restore, stream, delete, revoke the
      grant and see the lost state, re-pick. Then `bumpVersion`,
      `assembleRelease` (the mapping upload still points at
      `api.firedown.app` — fine, the name survives), `releaseStatus`,
      publish the APK, commit `status.json`.
- [ ] **1.8 Deactivate the mint keysets** the same day (cloud VPS:
      `UPDATE mint_keysets SET active = false;`), and watch `--audit` /
      the ntfy pings for anything the old APKs still do.

## Phase 2 — move `api.firedown.app` to the www host (`firedown-api` + ops)

Code is on `firedown-api` main since 2026-10-16 (`sync-api-provision.sh` nginx mode:
one server block, the three trusted CF headers, its own access log so the
`/status.json` ping stats never see API traffic, `client_max_body_size
256m` for the mapping PUT, `proxy_read_timeout 90s` for the 25 s long-poll,
no ACME — the Cloudflare origin certificate paths, AOP as
`ssl_verify_client`; a re-run keeps every `api.env` value, appended or
unknown ones included, since a694f70). The host move is runbook §11,
automated by `deploy/move-api-to-www.sh` (run from the laptop; done
2026-10-16):

- [x] **2.1** On the cloud VPS: stop `firedown-api`, `pg_dump firedown`,
      tar `<DataDir>/mappings`, copy `/etc/firedown/api.env`.
- [x] **2.2** On www: seed `api.env` from the copy BEFORE provisioning (so
      `keep()` carries the crash token, the ntfy URLs, the TURN secret and
      `MAX_ACCOUNTS`), run `sync-api-provision.sh` with `FIREDOWN_PROXY=nginx`
      and the origin-certificate paths, `pg_restore`, untar the mappings.
- [ ] **2.3** Flip the `api.firedown.app` DNS record to www (proxied), verify
      through the edge: `/v1/health` reports the commit just deployed; a
      crash send, a feedback send, a bookmark sync and a P2P share from a
      phone all land; the mapping PUT from a release build is accepted.
      Done through the edge 2026-10-16 (health on 2ea1b3e, relay
      credentials on, 7 mappings listed); the phone sends and the next
      release's mapping PUT are still to see.
- [x] **2.4** `firedown-website`: the vhost note on `sunset/api-vhost-note`
      (README + the nginx server block that returns 404 under `/ops/` is
      unaffected; the API block is its own server name).

## Phase 3 — destroy the cloud VPS (and the TURN box)

The step-by-step commands are `firedown-api` `deploy/deploy-runbook.md` §12
(on main since 7cdc03f); the boxes below are what it covers.

Gate: Phase 2 verified. The maintainer chose (2026-10-16) to go straight to
one VPS rather than wait for the previous version's update-ping share to
fall, so a user still on an old APK who never restored their hosted files
loses them at 3.3 — §12 step 0 counts them first.

- [x] **3.1 Cloudflare** (done differently, 2026-10-17: the 410 block is on
      www, but the maintainer DELETED the storage/mint DNS records instead of
      pointing them there — every record on the old IP is gone, so nothing
      can follow the IP to its next OVH tenant; old installs get a DNS
      failure instead of a 410): point `storage.firedown.app` and
      `mint.firedown.app` at the www host and serve `410 Gone` for both
      names (`FIREDOWN_GONE_FQDNS` on the api provisioner's nginx mode: one
      server block, no backend); keep the DNS records so the names resolve
      to the 410 rather than NXDOMAIN.
- [x] **3.2 Mint wind-down** (2026-10-17, `deploy/retire-cloud.sh`: keysets
      inactive, the donate page's offer is not this node's, channel balance 0,
      seed saved age-encrypted; two paid-but-undelivered Stripe quotes of
      2026-09-01, $6 each, listed for refund in the run's SUMMARY.txt): check first whether the donate page's BOLT12
      offer is this phoenixd's (`phoenix-cli getoffer`): if so, Lightning
      donations die with the box. Every keyset inactive (1.8), `--gc` run once
      more, read `mint_issued_counts` against storage's spent set
      (`storage-api --audit`) for the record, sweep the on-chain receipts to
      the cold wallet (consolidate in a low-fee window; raise the sweeping
      wallet's gap limit above what `--onchain-check` prints first), back
      up the phoenixd seed OFF the box, close its channel.
- [x] **3.3 Storage wind-down** (the R2 bucket was deleted before the units
      were stopped; final dump taken 2026-10-17): `storage-api --reap` with the grace set to
      0 is NOT the tool (it keys on `runout_at`); run `--purge-account` per
      remaining account after a final `pg_dump` for the record, then empty
      the R2 bucket and delete it, delete the Cloudflare R2 token.
- [ ] **3.4 Databases** (the final dumps are on the maintainer's laptop, the
      mint one age-encrypted; destroying the VPS is the open part): final `pg_dump` of `firedown_storage` and
      `firedown_mint` to cold storage (the mint dump is the ability to
      mint — `firedown-backup` refuses a plaintext one without an age
      recipient; keep it that way), then destroy the VPS.
- [x] **3.5 TURN — kept, on its own box** (maintainer, 2026-10-16). Shares
      between two phones on VPNs fail without it (tested), and on www it
      would lose its 443 listener to nginx and share the website's
      bandwidth. Nothing to do in the teardown; after the API move, www's
      `api.env` must carry `FIREDOWN_TURN_SECRET` = the box's
      `static-auth-secret`, or every share runs without a relay.
- [ ] **3.6** (superseded by 3.1's deletion; the 410 block on www can go with
      `FIREDOWN_GONE_FQDNS=none` whenever) Remove the `storage.firedown.app` / `mint.firedown.app` DNS
      records and the 410 block once the previous version's update-ping
      share reads zero for a month.

## Phase 4 — delete the server code and the website copy (after 3.4)

The app's part is done in release N. What remains:

- [ ] **4.1 `firedown-api`**: done on branch `sunset/delete-storage-mint`
      (f2a65aa), merge to main once the cloud VPS is gone. Delete `cmd/storage-api`, `cmd/mint-api`,
      `internal/storage`, `internal/mint`, `internal/shared/blind`, the
      storage/mint provisioners and their systemd units, the pairing
      routes (`/v1/pair/*`) and the storage CORS middleware's only consumer;
      keep `internal/shared/*` the bookmark binary still uses. `make
      vectors` stays idempotent; `go test ./...` green.
- [x] **4.2 `firedown-website`** (86e91ab and the security/privacy rewrites): delete `backup/` (the web client) and its
      `tools/` tests (`fd-pair-test.mjs`, `pair-seal.json`, `fd-api-
      integration.mjs`, `fd-crypto-selftest.mjs`), the footer's Backup link
      in all 19 copies (footer sweeps ENUMERATE the pages), the "Open it in
      this browser" link in `features.html`, and rewrite `/security` (19
      copies: no pairing SAS panel, no storage/mint hosts, the §08 table
      matches the app's `SECURITY.md`) and `/privacy` §03 (no storage
      service). `fd-links-test.mjs` asserts the absence.
- [ ] **4.3** (in f2a65aa with 4.1) Remove the `firedown-api` CORS allow-list for the web client
      (`FIREDOWN_STORAGE_CORS_ORIGINS` and its middleware), the R2 bucket
      policy having gone with the bucket.

## Status log

- 2026-10-12 — plan re-cut to one release (this file); Phase 1 code
  complete on `ccr-4d141886-ro0eii` pending the maintainer's build + device
  pass; Phase 2 code complete on `firedown-api` `sunset/api-to-www` and
  `firedown-website` `sunset/api-vhost-note`, host move pending.
- 2026-10-16 — one VPS: the maintainer decided www hosts the website and
  `firedown-api`, and the cloud VPS goes. `firedown-api` main carries the
  move (§11) and the teardown (§12, with the 410 block for the retired
  names); the code deletion waits on `sunset/delete-storage-mint`. Also
  found: the API's `api.env` on the cloud VPS had lost `FIREDOWN_TURN_SECRET`
  (`/v1/relay/challenge` answered "Relay is not configured."), so no P2P
  share had a relay; seed it on www from the coturn box's
  `static-auth-secret`.
- 2026-10-16 — Phase 2 done with `move-api-to-www.sh`: www provisioned
  (Postgres 18, nginx block, AOP off as on the cloud), the cloud copy
  stopped and disabled, 31 accounts / 27 bookmark docs / 14 crash reports
  and 14 mapping files copied and matched, `api.firedown.app` pointed at
  51.222.87.24. The cloud VPS keeps its stopped copy for a few days before
  Phase 3. Same day: Authenticated Origin Pulls turned on for www's
  api.firedown.app block (the zone's Global toggle was already on); a
  direct request now gets "400 No required SSL certificate was sent",
  through Cloudflare answers. A provisioner re-run keeps it on since
  firedown-api 58abb93.
- 2026-10-17 — the cloud VPS's storage units stopped (its R2 bucket was
  already deleted; the daily audit had pushed NoSuchBucket). www's database
  is backed up weekly (Sundays 03:17 UTC) to the R2 bucket
  `firedown-backups`, age-encrypted to a key on the maintainer's laptop,
  set up with firedown-api `deploy/setup-backup.sh`; the restore test passed
  (32 accounts restored = 32 live).
- 2026-10-17 — cloud VPS emptied with firedown-api `deploy/retire-cloud.sh`
  (money, dumps, every unit stopped, 410 on www) and every DNS record on its
  IP deleted. Its env files, origin-certificate key and phoenixd data were
  shredded and it was powered off; OVH deletes it at the end of the billing
  period (~2026-11-01). Left: merge `sunset/delete-storage-mint` (rebased on
  main as bb97f14, all checks green).
