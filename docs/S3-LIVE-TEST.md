# Backup to S3 — live test against Backblaze B2 or Cloudflare R2

`sh scripts/s3-harness/run.sh` covers the signing, the store and every
failure class against an in-JVM S3, moto and a real MinIO. It cannot cover
the page, Android's trust manager, or how a real provider answers. These are
the device steps for that. Use a debug build (so a failure is in logcat) and
a bucket that holds nothing else.

You need: a phone with the build, `adb`, and the AWS CLI on the laptop to
look at the bucket (`pip install awscli`; any S3 client works).

## 1. Make a bucket and a key limited to it

**Backblaze B2**

1. Buckets → Create a Bucket: private. Note the **Endpoint** on the bucket
   card, e.g. `s3.us-west-004.backblazeb2.com`.
2. Lifecycle Settings on the bucket → **Keep only the last version of the
   file**. B2 keeps every version by default, and an S3 delete only hides
   a file, so removed backups would keep counting against storage.
3. Application Keys → Add a New Application Key: access to **this bucket
   only**, Read and Write. Copy the keyID and the applicationKey (shown
   once).

**Cloudflare R2**

1. R2 → Create bucket.
2. R2 → Manage API tokens → Create API token: **Object Read & Write**,
   applied to **this bucket only**. Copy the Access Key ID and the Secret
   Access Key.
3. The endpoint is `https://<account id>.r2.cloudflarestorage.com` (shown
   with the token). The region is `auto`; leave the field empty. R2 aborts
   unfinished multipart uploads after 7 days by default.

Point the CLI at it (B2 example):

```sh
export AWS_ACCESS_KEY_ID=<key id> AWS_SECRET_ACCESS_KEY=<secret>
EP=https://s3.us-west-004.backblazeb2.com
B=<bucket>
aws s3 ls --recursive s3://$B/ --endpoint-url $EP
aws s3api list-multipart-uploads --bucket $B --endpoint-url $EP
```

## 2. Connect

Settings → Backup → create the recovery code if asked → "Choose where to
back up" → **S3 storage**. Enter the endpoint, bucket, key ID and secret;
leave "More options" closed. Connect.

- Expect the "Backup folder: Backblaze B2" (or "Cloudflare R2") snackbar
  and the folder row "`<bucket>` · S3 · Backblaze B2".
- The CLI listing must be EMPTY: the check's test object is gone.

## 3. Back up, list, stream, restore

1. Back up one small download (under 8 MiB: one PUT) and one large one
   (over 100 MiB: multipart, one 8 MiB part at a time).
2. The CLI listing shows `Firedown Backup/<account>/objects/<id>.fdvc` and
   `<id>.entry` for each, and `list-multipart-uploads` shows none. An
   object's size is the file's size plus 34 bytes per 8 MiB.
3. Backups list: both rows, with previews. Open the large one and seek
   around (one ranged GET per chunk).
4. Delete the local copy, Restore, and compare:
   `adb shell sha256sum /sdcard/Download/Firedown/<name>` against the
   original's hash.

## 4. Failures

| do this | expect |
|---|---|
| Start a large backup, then cancel it from its notification | No object, no sidecar, and `list-multipart-uploads` empty |
| Start a large backup and turn on airplane mode for 20 s mid-way | It carries on or retries (the part is resent), never a half object |
| Force-stop the app mid-upload (`adb shell am force-stop com.solarized.firedown`) | One unfinished upload remains; the first listing more than 24 h later aborts it |
| Delete or disable the key in the console, then back up | "Backup server unreachable" notification naming the provider; the Backup screen says "Can't reach your bucket"; Reconnect opens the page prefilled |
| On that page, enter a NEW key and its secret | Connects; backups resume |
| Reconnect with the same endpoint, bucket and key ID but an empty secret | Uses the saved secret |
| Change the bucket name and leave the secret empty | Connect stays disabled until a secret is typed: the saved one is never sent to another bucket |
| B2: set the region to another one (e.g. `eu-central-003`) under More options | Followed and saved, or "This bucket is in another region" under the region field; never a false OK |
| Turn off automatic time and set the phone one hour ahead, then back up | Works (one extra request to correct the clock) |
| B2: Caps & Alerts → a storage cap below the next file's size | "Storage full" notification, and the row says so; no endless retries |
| Settings → Backup → choose another place | The CLI listing is unchanged: nothing in the bucket is deleted |

## 5. Nothing secret in the log

While doing all of the above:

```sh
adb logcat -d | grep -i -E 'AWS4-HMAC|Signature=|X-Amz-Credential|<secret>'
```

must print nothing (put the actual secret in place of `<secret>`).

## 6. A home MinIO (plain http and a self-made certificate)

```sh
minio server ~/minio-data   # prints the API address and the root keys
```

Create a bucket in its console, then connect with `http://<laptop ip>:9000`:
the endpoint's helper changes to the unencrypted-address warning and the
button to "Connect anyway". For a self-made certificate, start MinIO with
`--certs-dir` holding a self-signed `public.crt`/`private.key`: expect
"Trust this server?" with the fingerprint, and after Trust a working
backup. Replace the certificate: backups stop with the Reconnect wording.
