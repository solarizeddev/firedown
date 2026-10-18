"""Planted bugs for scripts/s3-harness: each is applied to a COPY of the real
sources, and the harness must fail on every one (run.sh drives it).

  python3 -I mutants.py list                  names, one per line
  python3 -I mutants.py apply <name> <srcdir> applies one in place

A mutant whose search text is not found fails loudly: a refactor that moved
the code must update the mutant, not silently stop testing it.
"""
import os
import sys

SYNC = "com/solarized/firedown/sync/"

MUTANTS = {
    "signer-plus-unreserved": (
        "S3Signer.java",
        "|| c == '-' || c == '.' || c == '_' || c == '~';",
        "|| c == '-' || c == '.' || c == '_' || c == '~' || c == '+';",
        "the signer treats '+' as unreserved (botocore and every signed request disagree)"),
    "no-skew-retry": (
        "S3Client.java",
        "boolean skewFixed = false;",
        "boolean skewFixed = true;",
        "a skewed clock is not corrected"),
    "never-transient": (
        "S3Client.java",
        "            return status != 501 && status != 507;",
        "            return false;",
        "5xx and 429 are not retried"),
    "no-retry-after": (
        "S3Client.java",
        "        if (asked >= 0) {\n            wait = asked;",
        "        if (asked < 0) {\n            wait = asked;",
        "Retry-After is ignored"),
    "range-start-unchecked": (
        "S3Client.java",
        "                    if (start != offset) {",
        "                    if (start != offset && false) {",
        "a 206 for another range is accepted"),
    "one-big-put": (
        "S3Store.java",
        "        return chunksPerPart * STRIDE;",
        "        return Math.max(length, chunksPerPart * STRIDE);",
        "every object is one PUT (no multipart, no part retry)"),
    "no-abort": (
        "S3Store.java",
        "                s3.abortMultipart(key, id, \"abort the upload\");",
        "                if (id.isEmpty()) {\n                    s3.abortMultipart(key, id, \"abort the upload\");\n                }",
        "a failed or cancelled upload leaves its parts"),
    "sweep-ignores-open": (
        "S3Store.java",
        "                if (openUploads.contains(u.uploadId)) {\n                    continue;\n                }",
        "",
        "the stale-upload sweep aborts an upload this store has open"),
    "no-quota": (
        "S3Client.java",
        "        if (isQuota(status, c, m)) {",
        "        if (false) {",
        "a storage cap reads as access denied, not full"),
    "reuse-any-endpoint": (
        "S3Check.java",
        "            if (!sameKeys) {\n                return Result.of(Kind.AUTH_FAILED);\n            }",
        "",
        "a saved secret is sent with any endpoint, bucket or key"),
    "constant-validator": (
        "S3Store.java",
        "        return o.etag != null ? \"e:\" + o.etag : \"s:\" + o.size + \":\" + o.modified;",
        "        return \"same\";",
        "a rewritten sidecar is served from the cache"),
    "migrate-always-folder": (
        "BackupStores.java",
        "new WebDavSettings(context).isConfigured() ? Place.WEBDAV : Place.FOLDER;",
        "Place.FOLDER;",
        "an install with a WebDAV server migrates to the folder"),
    "secret-in-clear": (
        "S3Settings.java",
        ".putString(KEY_SECRET, seal(config.secret))",
        ".putString(KEY_SECRET, config.secret)",
        "the secret is stored unsealed"),
    "choose-keeps-others": (
        "BackupStores.java",
        "        if (place != Place.S3) {\n            new S3Settings(context).clear();\n        }",
        "",
        "choosing another place keeps the bucket's keys"),
    "no-region-follow": (
        "S3Check.java",
        "        if (named == null || named.isEmpty() || named.equals(config.region)) {",
        "        if (true) {",
        "a wrong region the server names is not followed"),
    "no-virtual-fallback": (
        "S3Check.java",
        "            boolean tryOther = r.kind == Kind.NO_SUCH_BUCKET || r.kind == Kind.WRONG_REGION",
        "            boolean tryOther = false && r.kind == Kind.NO_SUCH_BUCKET || r.kind == Kind.WRONG_REGION",
        "AUTO never tries virtual-hosted"),
}


def main():
    if sys.argv[1] == "list":
        for name in MUTANTS:
            print(name)
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
