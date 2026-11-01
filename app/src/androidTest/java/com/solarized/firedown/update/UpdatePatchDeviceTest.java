package com.solarized.firedown.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.UpdateApkVerifier;
import com.solarized.firedown.UpdateInstalledApk;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * The parts of a delta update only a phone can answer: XZ for Java working
 * after D8 dexed it (it is a multi-release jar), the app reading and hashing
 * its own installed APK, and an APK REBUILT from that APK by a patch passing
 * the app's signature check, as an update's would.
 *
 * The patch is made here without the generator (which needs ~3 GB of heap):
 * one control entry copying the whole installed APK, with a diff stream of
 * zeros, so the rebuilt file is the installed APK byte for byte. Also: the
 * applier refuses that patch against a different base, and the .xz path.
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.update.UpdatePatchDeviceTest
 * </pre>
 * Needs about twice the APK's size free in the app's cache folder.
 */
@RunWith(AndroidJUnit4.class)
public class UpdatePatchDeviceTest {

    private Context mApp;
    private File mDir;

    @Before
    public void setUp() {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        mDir = new File(mApp.getCacheDir(), "update-patch-device-test");
        deleteTree(mDir);
        assertTrue(mDir.mkdirs());
    }

    @After
    public void tearDown() {
        deleteTree(mDir);
    }

    @Test
    public void theInstalledApkIsReadableAndItsHashIsCached() throws Exception {
        File apk = UpdateInstalledApk.file(mApp);
        assertNotNull("installed as split APKs, or base.apk unreadable", apk);
        assertTrue(apk.canRead());
        String direct = UpdatePatch.sha256Hex(apk, null);
        long start = System.nanoTime();
        String first = UpdateInstalledApk.sha256(mApp);
        long firstMs = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        String second = UpdateInstalledApk.sha256(mApp);
        long secondMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(direct, first);
        assertEquals(direct, second);
        assertTrue("the cached hash took " + secondMs + " ms (first " + firstMs + " ms)", secondMs < 200);
    }

    @Test
    public void aPatchRebuildsTheInstalledApkAndItPassesTheSignatureCheck() throws Exception {
        File installed = UpdateInstalledApk.file(mApp);
        assertNotNull(installed);
        String sha = UpdatePatch.sha256Hex(installed, null);
        File patch = identityPatch(installed, sha);
        File out = new File(mDir, "rebuilt.apk");

        long start = System.nanoTime();
        String rebuilt = UpdatePatch.apply(installed, patch, out, sha, null);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals("rebuilt in " + ms + " ms", sha, rebuilt);
        assertEquals(installed.length(), out.length());
        assertTrue(UpdateApkVerifier.verifySha256(out, sha));
        assertTrue("a rebuilt APK fails the signature check", UpdateApkVerifier.verifySignature(mApp, out));
    }

    @Test
    public void thePatchIsRefusedAgainstAnotherBase() throws Exception {
        File installed = UpdateInstalledApk.file(mApp);
        assertNotNull(installed);
        String sha = UpdatePatch.sha256Hex(installed, null);
        File patch = identityPatch(installed, sha);
        // Same length, one byte different: only the output hash can tell.
        File other = new File(mDir, "other.apk");
        Files.copy(installed.toPath(), other.toPath());
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(other, "rw")) {
            raf.seek(other.length() / 2);
            int b = raf.read();
            raf.seek(other.length() / 2);
            raf.write(b ^ 1);
        }
        try {
            UpdatePatch.apply(other, patch, new File(mDir, "x.apk"), null, null);
            fail("applied to a different base");
        } catch (UpdatePatch.PatchException expected) {
            // refused
        }
    }

    @Test
    public void xzRoundTripsOnTheDevice() throws Exception {
        byte[] data = new byte[3_000_000];
        new java.util.Random(7).nextBytes(data);
        for (int i = 0; i < data.length; i += 7) {
            data[i] = 0;
        }
        File xz = new File(mDir, "data.xz");
        try (OutputStream o = new XZOutputStream(new FileOutputStream(xz), new LZMA2Options())) {
            o.write(data);
        }
        File out = new File(mDir, "data");
        String sha = UpdatePatch.unxz(xz, out, data.length, null);
        assertArrayEquals(data, Files.readAllBytes(out.toPath()));
        assertEquals(UpdatePatch.toHex(MessageDigest.getInstance("SHA-256").digest(data)), sha);
    }

    /** One entry: the whole base plus a diff of zeros, i.e. the base itself. */
    private File identityPatch(File base, String sha) throws IOException {
        long size = base.length();
        File diff = new File(mDir, "diff.xz");
        try (OutputStream o = new XZOutputStream(new FileOutputStream(diff), new LZMA2Options(0))) {
            byte[] zeros = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(left, zeros.length);
                o.write(zeros, 0, n);
                left -= n;
            }
        }
        byte[] ctrl = xz(longs(size, 0, 0));
        byte[] extra = xz(new byte[0]);
        byte[] shaBytes = hexToBytes(sha);
        File patch = new File(mDir, "identity.fdpatch");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(patch))) {
            out.write(UpdatePatch.MAGIC);
            out.writeLong(size);
            out.writeLong(size);
            out.write(shaBytes);
            out.write(shaBytes);
            out.writeLong(ctrl.length);
            out.writeLong(diff.length());
            out.writeLong(extra.length);
            out.write(ctrl);
            Files.copy(diff.toPath(), out);
            out.write(extra);
        }
        return patch;
    }

    private static byte[] longs(long... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (long v : values) {
            out.writeLong(v);
        }
        return bytes.toByteArray();
    }

    private static byte[] xz(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream o = new XZOutputStream(bytes, new LZMA2Options(0))) {
            o.write(data);
        }
        return bytes.toByteArray();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }
}
