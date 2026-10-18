"""Planted bugs for scripts/backup-sim-harness: each is applied to a COPY of
the real sources, only the mutated file is recompiled, and the simulation
must report at least one regression (a failing check) on every one. run.sh drives it.

  python3 -I mutants.py list                  names, one per line
  python3 -I mutants.py file <name>           the file a mutant changes
  python3 -I mutants.py apply <name> <srcdir> applies one in place

A mutant whose search text is not found exactly once fails loudly: a
refactor that moved the code must update the mutant, not silently stop
testing it.
"""
import os
import sys

SYNC = "com/solarized/firedown/sync/"

MUTANTS = {
    "conflict-copy-wins": (
        "BackupLayout.java",
        "                copiesOf(id).add(item);",
        "                entries.put(id, item);",
        "a provider's conflict copy listed after the sidecar replaces it"),
    "no-bare-403-skew": (
        "S3Client.java",
        "\n                        || (status == 403 && code.isEmpty() && dateFarOff(response));",
        ";",
        "a skewed clock answered by a bodiless 403 (a HEAD) is never corrected"),
    "dot-prefix-kept": (
        "S3Check.java",
        "if (segment.isEmpty() || segment.equals(\".\") || segment.equals(\"..\")) {",
        "if (segment.isEmpty()) {",
        "a prefix keeps its . and .. segments"),
    "orphan-sweep-ignores-age": (
        "BackupLayout.java",
        "            if (object.modified > 0 && now - object.modified > ORPHAN_MIN_AGE_MS) {",
        "            if (true) {",
        "a listing sweeps every sidecar-less object, another phone's in-flight one included"),
    "stale-upload-sweep-ignores-age": (
        "S3Store.java",
        "                if (u.initiated > 0 && now - u.initiated > STALE_UPLOAD_MS) {",
        "                if (true) {",
        "a listing aborts every unfinished upload, another phone's fresh one included"),
    "s3-sidecar-cache-ignores-etag": (
        "S3Store.java",
        "        return o.etag != null ? \"e:\" + o.etag : \"s:\" + o.size + \":\" + o.modified;",
        "        return \"same\";",
        "another phone's rewrite of a sidecar is never seen"),
    "dav-sidecar-cache-ignores-etag": (
        "WebDavStore.java",
        "        return r.etag != null ? \"e:\" + r.etag : \"s:\" + r.size + \":\" + r.modified;",
        "        return \"same\";",
        "another phone's rewrite of a sidecar is never seen (WebDAV)"),
    "delete-all-takes-every-account": (
        "S3Store.java",
        "        String prefix = accountPrefix(account);",
        "        String prefix = rootPrefix();",
        "delete-all erases every recovery code's backups in the bucket"),
    "no-skew-learning": (
        "S3Client.java",
        "                    if (learnSkew(response, error)) {",
        "                    if (false) {",
        "a phone with a wrong clock is never corrected"),
    "orphan-age-by-phone-clock": (
        "S3Store.java",
        "                serverNow());\n        maybeSweepUploads(account);",
        "                S3Client.phoneClock.getAsLong());\n        maybeSweepUploads(account);",
        "orphans are aged by the phone's own clock, not the server's"),
    "deleted-chunk-reads-as-server-error": (
        "S3Client.java",
        "            Call call = new Call(\"GET\", key, what).allowMissing().header(\"Range\",",
        "            Call call = new Call(\"GET\", key, what).header(\"Range\",",
        "a chunk deleted by another phone reads as a server error, not as gone"),
    "listing-reads-one-page": (
        "S3Store.java",
        "        } while (token != null);",
        "        } while (false);",
        "a listing reads only its first page"),
    "delete-all-leaves-uploads": (
        "S3Store.java",
        "                if (!openUploads.contains(u.uploadId)) {\n                    s3.abortMultipart(u.key, u.uploadId, \"abort an upload\");",
        "                if (false) {\n                    s3.abortMultipart(u.key, u.uploadId, \"abort an upload\");",
        "delete-all leaves the account's unfinished uploads (billed parts) behind"),
}


def main():
    if sys.argv[1] == "list":
        for name in MUTANTS:
            print(name)
        return
    if sys.argv[1] == "file":
        print(MUTANTS[sys.argv[2]][0])
        return
    name, src = sys.argv[2], sys.argv[3]
    file, old, new, _ = MUTANTS[name]
    path = os.path.join(src, SYNC, file)
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if text.count(old) != 1:
        sys.exit("mutant %s: search text found %d times in %s" % (name, text.count(old), file))
    with open(path, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new))
    print(MUTANTS[name][3])


if __name__ == "__main__":
    main()
