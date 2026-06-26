package com.solarized.firedown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JVM unit tests for the update-APK integrity gate — the SHA-256 half of
 * UpdateApkVerifier. (The signature half needs PackageManager and is covered
 * by the instrumented/manual plan, not here.) This is the check that decides
 * whether a downloaded APK is trusted enough to prompt the user to install, so
 * the contract is pinned: an exact content-hash match verifies, and ANYTHING
 * else — wrong hash, no/blank expected hash, missing file — does not.
 *
 * The expected digest is computed with java.security.MessageDigest, an
 * independent oracle from the production commons-codec path, so a regression in
 * either implementation fails the test rather than both drifting together.
 */
public class UpdateApkVerifierTest {

    private static File writeTemp(byte[] bytes) throws IOException {
        File f = File.createTempFile("fd-update-test", ".apk");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    @Test
    public void matchingDigestVerifies() throws Exception {
        byte[] payload = "pretend-apk-bytes".getBytes(StandardCharsets.UTF_8);
        File apk = writeTemp(payload);
        assertTrue(UpdateApkVerifier.verifySha256(apk, sha256Hex(payload)));
    }

    @Test
    public void matchingDigestIsCaseInsensitive() throws Exception {
        byte[] payload = "another-apk".getBytes(StandardCharsets.UTF_8);
        File apk = writeTemp(payload);
        // Manifests may carry the digest in either case; both must verify.
        assertTrue(UpdateApkVerifier.verifySha256(apk, sha256Hex(payload).toUpperCase()));
    }

    @Test
    public void wrongDigestRejected() throws Exception {
        byte[] payload = "real-bytes".getBytes(StandardCharsets.UTF_8);
        File apk = writeTemp(payload);
        // Digest of DIFFERENT content — i.e. a tampered/mismatched APK.
        String wrong = sha256Hex("tampered-bytes".getBytes(StandardCharsets.UTF_8));
        assertFalse(UpdateApkVerifier.verifySha256(apk, wrong));
    }

    @Test
    public void nullOrEmptyExpectedRejected() throws Exception {
        File apk = writeTemp("x".getBytes(StandardCharsets.UTF_8));
        assertFalse(UpdateApkVerifier.verifySha256(apk, null));
        assertFalse(UpdateApkVerifier.verifySha256(apk, ""));
    }

    @Test
    public void missingFileRejected() throws Exception {
        File missing = File.createTempFile("fd-update-missing", ".apk");
        String hash = sha256Hex("gone".getBytes(StandardCharsets.UTF_8));
        // A recorded digest with no file on disk must never verify true.
        assertTrue(missing.delete());
        assertFalse(UpdateApkVerifier.verifySha256(missing, hash));
    }
}
