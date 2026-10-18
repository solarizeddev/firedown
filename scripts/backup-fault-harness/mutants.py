"""Planted bugs for scripts/backup-fault-harness: each is applied to a COPY of
the real sources, and the harness must fail on every one (run.sh drives it).

  python3 -I mutants.py list                  names, one per line
  python3 -I mutants.py apply <name> <srcdir> applies one in place

A mutant whose search text is not found fails loudly: a refactor that moved
the code must update the mutant, not silently stop testing it.
"""
import os
import sys

SYNC = "com/solarized/firedown/sync/"

MUTANTS = {
    "runtime-escapes": (
        "S3Client.java",
        "} catch (IOException | RuntimeException failure) {",
        "} catch (IOException failure) {",
        "a RuntimeException out of OkHttp (an overflowing Retry-After) escapes the S3 client"),
    "moved-saved": (
        "WebDavCheck.java",
        "if (!p.finalUrl.host().equalsIgnoreCase(typed.host())) {",
        "if (false) {",
        "a WebDAV redirect to another host is saved as the server"),
    "userinfo-kept": (
        "S3Check.java",
        "return url.newBuilder().username(\"\").password(\"\").encodedPath(path)",
        "return url.newBuilder().encodedPath(path)",
        "user info typed into an S3 endpoint is kept"),
    "s3-follows-redirects": (
        "S3Client.java",
        ".followRedirects(false)",
        ".followRedirects(true)",
        "the S3 client follows a redirect to another host"),
    "webdav-accepts-downgrade": (
        "WebDavCheck.java",
        "if (p.code == 207 && p.self != null && p.self.collection && !downgraded) {",
        "if (p.code == 207 && p.self != null && p.self.collection) {",
        "an https address redirected to http is accepted by the check"),
    "config-prints-secret": (
        "S3Config.java",
        "                + (pinSha256 == null ? \"\" : \", pinned\") + \"}\";",
        "                + \", secret=\" + secret + \"}\";",
        "S3Config.toString prints the secret key"),
    "no-abort-on-cancel": (
        "S3Store.java",
        "                s3.abortMultipart(key, id, \"abort the upload\");",
        "                if (id.isEmpty()) {\n                    s3.abortMultipart(key, id, \"abort the upload\");\n                }",
        "a cancelled or failed multipart upload leaves its parts on the server"),
    "error-body-unbounded": (
        "S3Client.java",
        "private static final long MAX_ERROR_BYTES = 64 * 1024;",
        "private static final long MAX_ERROR_BYTES = Long.MAX_VALUE;",
        "an error body is read whole, however large"),
    "list-pages-unbounded": (
        "S3Store.java",
        "            if (++pages > MAX_LIST_PAGES) {",
        "            if (++pages > Integer.MAX_VALUE) {",
        "a listing whose continuation token never ends is followed forever"),
    "doctype-allowed": (
        "S3Client.java",
        "        if (containsDoctype(xml)) {\n            throw new IOException(what + \": answer carries a DOCTYPE\");",
        "        if (false) {\n            throw new IOException(what + \": answer carries a DOCTYPE\");",
        "an S3 answer with a DOCTYPE (entities) is parsed"),
    "sidecar-unbounded": (
        "BackupLayout.java",
        "            if (total > max) {\n                throw new IOException(\"more than \" + max + \" bytes\");\n            }\n",
        "",
        "a response body is read whole past its cap (sidecars, listings)"),
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
