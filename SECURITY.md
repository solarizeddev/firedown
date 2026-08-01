# Security

Firedown is a browser and downloader for Android. It has no accounts, no
telemetry and no analytics, and its optional paid feature — Cloud Backup — is
built so that the server storing your files **cannot read them**.

This file is for people who want to check that claim in source rather than take
it, and for anyone who has found a way to break it. There is a plain-language
version of the same material at <https://firedown.app/security>.

## Reporting a vulnerability

**Email <info@solarized.dev>** instead of opening a public issue — including for
the server-side services, which are not in this repository.

Helpful but not required: what you did, what you expected, what happened
instead, and the build you were on. A clear description of the flaw is worth
more than a working exploit.

This is a very small project. You will get a human reply, though not
necessarily a fast one, and there is no bug bounty. You will get credit if you
want it, and a straight answer about whether and when it is fixed.

## What is in this repository

The Android app. **Everything that encrypts anything runs here**, which is the
point: the security of Cloud Backup does not depend on trusting code you cannot
read.

| Area | Where |
|---|---|
| Key derivation from the recovery code | `app/src/main/java/com/solarized/firedown/sync/crypto/SyncIdentity.java` |
| Per-file encryption for backed-up files | `.../sync/crypto/VaultCrypto.java` |
| Encrypted bookmark + manifest blobs | `.../sync/crypto/BookmarkBlob.java` |
| Request signing (the signed byte string) | `.../sync/crypto/Canonical.java` |
| Browser pairing handshake | `.../sync/crypto/PairSeal.java` |
| Anonymous payment tokens (blind signatures) | `.../sync/crypto/BlindSignature.java` |
| Upload / download pipeline | `.../sync/VaultEngine.java` |

The tests beside them are worth reading too. Several assert **exact bytes**
against shared vectors rather than merely checking that a round trip works — a
round trip succeeds happily against an implementation that is wrong but
self-consistent, which is a real failure mode we have hit. `VaultCryptoTest`
and `PairSealTest` are the clearest examples.

Two other clients implement the same formats: the web client at
`firedown.app/backup/` (in the `firedown-website` repository) and the server.
**The server is not public.** It does not need to be in order to check the
claims below — encryption, key derivation and pairing all happen on the client,
so a reader of this repository can confirm that no key is ever sent anywhere.

## Threat model

The server is treated as **untrusted**: assume it could be compromised, seized,
subpoenaed, or misconfigured by us. Your files should stay unreadable in all of
those cases.

What holds under that assumption:

- **Files are encrypted on the device before upload**, each under its own key.
  Encrypted pieces travel from the phone straight to object storage.
- **Every key derives from a recovery code that never leaves the device.** It
  is not transmitted during backup, restore, or browser pairing. We hold no
  copy and cannot generate one.
- **The file index is itself an encrypted blob.** Names, types, dates and
  preview images are inside it. The server stores and returns it; it cannot
  open it.
- **Each encrypted piece is bound to its position in a specific file**, so a
  hostile server cannot reorder pieces, swap them between files, or replay one
  file's data into another without decryption failing.
- **Payments are unlinkable to what they buy.** Storage credit is issued as an
  anonymous token via a blind signature, so no record connects a payment to the
  account that spends it.

What does **not** hold — listed because a threat model that records only wins
is useless:

- **Metadata is visible.** File sizes, file counts, backup times and your IP
  address are not hidden, and cannot be without a substantially more expensive
  design.
- **A compromised device defeats all of it.** Encryption happens on the phone;
  if the phone is compromised, plaintext is available there.
- **A lost recovery code is unrecoverable**, deliberately. There is no reset
  path, because a reset path is exactly what would let someone else in.
- **The Safe Folder is local-only.** It is excluded from Cloud Backup entirely
  and is protected by your device lock, not by the recovery code.

## Known limits we have not fixed

Real, currently true, and disclosed on purpose.

- **The web client's Ed25519 signing is not constant-time**, and cannot be:
  JavaScript's BigInt arithmetic is itself variable-time. The obvious
  secret-dependent branch has been removed, which reduces the leaked signal
  without eliminating it. The real fix is native `Ed25519` in WebCrypto once
  browser support is universal. The Android client is unaffected — it uses a
  constant-time library implementation.
- **HTTP header spoofing cannot defeat TLS fingerprinting.** Where the
  downloader reproduces a browser's request headers to fetch media, a server
  that also fingerprints the TLS handshake (JA3/JA4) can still tell the
  difference. We do not claim otherwise.
- **Translated privacy and security pages are not legally reviewed.** The
  English versions govern, and each translated page says so.

## Scope

In scope: the Android app in this repository, the web client, and the
server-side services behind `api.firedown.app`, `storage.firedown.app` and
`mint.firedown.app`.

Out of scope: findings that require a rooted or already-compromised device;
vulnerabilities in GeckoView itself (report those to
[Mozilla](https://www.mozilla.org/en-US/security/bug-bounty/)); missing
hardening headers on the marketing site with no demonstrated impact; and
scanner output with no analysis attached.

Anything that lets a party other than the user read backed-up file contents,
file names, or a recovery code is in scope and serious, however unlikely the
path.
