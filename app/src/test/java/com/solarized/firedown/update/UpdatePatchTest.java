package com.solarized.firedown.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * The update patch applier (UpdatePatch), against patches from the release
 * generator (UpdateDiff) and against hand-made hostile ones. The applier runs
 * on bytes from the network, so the contract pinned here is: a correct patch
 * rebuilds the new file exactly; a patch that breaks a rule ends in a
 * PatchException; a changed patch is refused or still rebuilds exactly the
 * new file — it is never returned as good with different bytes.
 */
public class UpdatePatchTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ── round trips ──────────────────────────────────────────────────────

    @Test
    public void rebuildsEditedFilesExactly() throws Exception {
        Random random = new Random(1);
        byte[] old = randomBytes(random, 300_000);
        byte[] nw = old.clone();
        // A changed region, an insertion, a deletion and a moved block.
        for (int i = 1000; i < 1300; i++) {
            nw[i] ^= 0x5a;
        }
        nw = concat(Arrays.copyOfRange(nw, 0, 50_000), randomBytes(random, 4_000),
                Arrays.copyOfRange(nw, 50_000, 120_000), Arrays.copyOfRange(nw, 150_000, 300_000),
                Arrays.copyOfRange(old, 10_000, 30_000));
        assertRoundTrip(old, nw);
    }

    @Test
    public void edgeSizes() throws Exception {
        Random random = new Random(2);
        byte[] some = randomBytes(random, 70_000);
        assertRoundTrip(new byte[0], new byte[0]);
        assertRoundTrip(new byte[0], some);
        assertRoundTrip(some, new byte[0]);
        assertRoundTrip(some, some.clone());
        assertRoundTrip(new byte[] {1}, new byte[] {2});
        assertRoundTrip(new byte[65_536], new byte[65_537]);
        assertRoundTrip(randomBytes(random, 1000), randomBytes(random, 1000));
    }

    @Test
    public void repetitiveData() throws Exception {
        byte[] old = new byte[200_000];
        byte[] nw = new byte[210_000];
        for (int i = 0; i < nw.length; i++) {
            nw[i] = (byte) (i % 7 == 0 ? 1 : 0);
        }
        assertRoundTrip(old, nw);
    }

    @Test
    public void identicalFilesMakeATinyPatch() throws Exception {
        byte[] data = randomBytes(new Random(3), 1_000_000);
        byte[] patch = UpdateDiff.diff(data, data.clone());
        assertTrue("patch of identical 1 MB files is " + patch.length + " bytes", patch.length < 2_000);
        assertRoundTrip(data, data.clone());
    }

    @Test
    public void applyReturnsTheNewHash() throws Exception {
        byte[] old = randomBytes(new Random(4), 5000);
        byte[] nw = randomBytes(new Random(5), 6000);
        File oldFile = write("old", old);
        File patch = write("patch", UpdateDiff.diff(old, nw));
        File out = tmp.newFile("out");
        String sha = UpdatePatch.apply(oldFile, patch, out, UpdatePatch.toHex(UpdateDiff.sha256(old)), null);
        assertEquals(UpdatePatch.toHex(UpdateDiff.sha256(nw)), sha);
    }

    // ── a patch that was changed in any way fails ────────────────────────

    /**
     * A changed byte is refused — or, in the rare place where the change
     * decodes to the same data (the LZMA properties byte of a stream that is
     * mostly zeros reads the same as lc=2 or lc=3), the output is still
     * exactly the new file: the applier never returns a wrong file. (In the
     * app a changed patch never gets this far: its SHA-256 is checked against
     * status.json first.)
     */
    @Test
    public void everySingleByteCorruptionIsRefusedOrHarmless() throws Exception {
        Random random = new Random(6);
        byte[] old = randomBytes(random, 3000);
        byte[] nw = old.clone();
        nw[100] = 1;
        nw = concat(nw, randomBytes(random, 50));
        byte[] patch = UpdateDiff.diff(old, nw);
        File oldFile = write("old", old);
        int harmless = 0;
        int tried = 0;
        for (int i = 0; i < patch.length; i++) {
            for (int flip : new int[] {0x01, 0x80, 0xff}) {
                byte[] bad = patch.clone();
                bad[i] ^= (byte) flip;
                tried++;
                if (refusedOrExact("byte " + i + " ^ " + flip, oldFile, bad, nw)) {
                    harmless++;
                }
            }
        }
        assertTrue(harmless + " of " + tried + " changes applied", harmless * 100 < tried);
    }

    /** True when the patch applied (to exactly {@code nw}); false when refused. */
    private boolean refusedOrExact(String what, File oldFile, byte[] patch, byte[] nw) throws IOException {
        File out = tmp.newFile();
        try {
            UpdatePatch.apply(oldFile, write("bad", patch), out, null, null);
        } catch (UpdatePatch.PatchException expected) {
            return false;
        } catch (IOException | RuntimeException e) {
            fail(what + ": failed with " + e + " instead of a PatchException");
        }
        assertArrayEquals(what + ": applied to different bytes", nw, Files.readAllBytes(out.toPath()));
        return true;
    }

    @Test
    public void everyTruncationIsRefused() throws Exception {
        byte[] old = randomBytes(new Random(7), 2000);
        byte[] nw = randomBytes(new Random(8), 2100);
        byte[] patch = UpdateDiff.diff(old, nw);
        File oldFile = write("old", old);
        for (int n = 0; n < patch.length; n++) {
            assertRefused("truncated to " + n, oldFile, Arrays.copyOf(patch, n));
        }
        assertRefused("one byte appended", oldFile, concat(patch, new byte[] {0}));
    }

    @Test
    public void theWrongOldFileIsRefused() throws Exception {
        Random random = new Random(9);
        byte[] old = randomBytes(random, 4000);
        byte[] nw = old.clone();
        nw[5] = 7;
        File patch = write("patch", UpdateDiff.diff(old, nw));
        // Same length, one byte different: only the output hash can tell.
        byte[] other = old.clone();
        other[3000] ^= 1;
        assertApplyFails(write("other", other), patch, null);
        // A different length is caught before anything is read.
        assertApplyFails(write("short", Arrays.copyOf(old, 3999)), patch, null);
        // The caller's hash of the installed APK must be the patch's base.
        assertApplyFails(write("old", old), patch, "00" + UpdatePatch.toHex(UpdateDiff.sha256(old)).substring(2));
    }

    // ── hand-made patches that break a rule ──────────────────────────────

    @Test
    public void controlEntriesAreBoundsChecked() throws Exception {
        byte[] old = randomBytes(new Random(10), 100);
        byte[] nw = randomBytes(new Random(11), 100);
        File oldFile = write("old", old);
        // Negative lengths.
        assertCraftRefused(oldFile, old, nw, new long[][] {{-1, 101, 0}}, new byte[0], nw);
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, -5, 0}}, new byte[0], nw);
        // Reading past the old file.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 0, 50}, {60, 40, 0}}, new byte[60], tail(nw, 40));
        // Writing past the new file.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 200, 0}}, new byte[0], concat(nw, nw));
        // Seeking before the start or past the end of the old file.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 50, -1}, {0, 50, 0}}, new byte[0], nw);
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 50, 101}, {0, 50, 0}}, new byte[0], nw);
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 50, Long.MAX_VALUE}, {0, 50, 0}}, new byte[0], nw);
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 50, Long.MIN_VALUE}, {0, 50, 0}}, new byte[0], nw);
        // An entry that produces nothing, after the first.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 50, 0}, {0, 0, 0}, {0, 50, 0}}, new byte[0], nw);
        // Control entries left after the new file is complete.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 100, 0}, {0, 0, 0}}, new byte[0], nw);
        // Diff or extra bytes left over.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 100, 0}}, new byte[1], nw);
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 100, 0}}, new byte[0], concat(nw, new byte[1]));
        // Diff or extra bytes missing.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 100, 0}}, new byte[0], tail(nw, 99));
        assertCraftRefused(oldFile, old, nw, new long[][] {{50, 50, 0}}, new byte[49], tail(nw, 50));
        // A control stream cut inside a triple.
        assertCraftRefused(oldFile, old, nw, new long[][] {{0, 100}}, new byte[0], nw);
    }

    @Test
    public void handMadePatchesThatFollowTheRulesWork() throws Exception {
        byte[] old = randomBytes(new Random(12), 100);
        byte[] nw = concat(Arrays.copyOfRange(old, 40, 90), new byte[] {9, 9, 9});
        File oldFile = write("old", old);
        // A pure seek first is allowed, then 50 bytes of old + 3 extra.
        byte[] patch = craft(old, nw, new long[][] {{0, 0, 40}, {50, 3, 0}}, new byte[50], new byte[] {9, 9, 9});
        File out = tmp.newFile();
        UpdatePatch.apply(oldFile, write("patch", patch), out, null, null);
        assertArrayEquals(nw, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void headerChecks() throws Exception {
        byte[] old = randomBytes(new Random(13), 100);
        byte[] nw = randomBytes(new Random(14), 100);
        File oldFile = write("old", old);
        byte[] good = UpdateDiff.diff(old, nw);
        byte[] magic = good.clone();
        magic[0] = 'X';
        assertRefused("magic", oldFile, magic);
        byte[] huge = good.clone();
        putLong(huge, 16, UpdatePatch.MAX_FILE_SIZE + 1);
        assertRefused("new size over the cap", oldFile, huge);
        byte[] negative = good.clone();
        putLong(negative, 88, -1);
        assertRefused("negative stream length", oldFile, negative);
        byte[] overflow = good.clone();
        putLong(overflow, 88, Long.MAX_VALUE);
        putLong(overflow, 96, Long.MAX_VALUE);
        assertRefused("stream lengths that overflow", oldFile, overflow);
    }

    @Test
    public void aStreamThatExpandsFarPastTheFileStopsEarly() throws Exception {
        byte[] old = new byte[10];
        byte[] nw = new byte[10];
        // 64 MiB of zero triples compress to a few KB; the second zero triple
        // must be refused without inflating the rest.
        ByteArrayOutputStream ctrl = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(new XZOutputStream(ctrl, new LZMA2Options()))) {
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 64; i++) {
                data.write(zeros);
            }
        }
        byte[] patch = assemble(old, nw, ctrl.toByteArray(), xz(new byte[0]), xz(new byte[0]));
        // The applier polls the cancel check once per triple and per chunk:
        // counting the polls counts the work it did before refusing.
        int[] polls = {0};
        try {
            UpdatePatch.apply(write("old", old), write("bomb", patch), tmp.newFile(), null, () -> {
                polls[0]++;
                return false;
            });
            fail("zero-triple bomb applied");
        } catch (UpdatePatch.PatchException expected) {
            assertTrue("read " + polls[0] + " triples before refusing", polls[0] < 10);
        }
    }

    @Test
    public void anXzStreamAskingForTooMuchMemoryIsRefused() throws Exception {
        byte[] stream = xz(new byte[] {1, 2, 3});
        // Byte 30 = a 128 MiB dictionary: valid, decodable, over the limit.
        File in = write("big-dict.xz", withDictionaryByte(stream, 30));
        try {
            UpdatePatch.unxz(in, tmp.newFile(), 1000, null);
            fail("a 128 MiB dictionary was accepted");
        } catch (UpdatePatch.PatchException expected) {
            // refused before anything is allocated
        }
    }

    @Test
    public void theGeneratorFoldsEntriesThatProduceNothing() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        UpdateDiff.Controls controls = new UpdateDiff.Controls(new DataOutputStream(bytes));
        controls.add(0, 0, 5);
        controls.add(0, 0, 2);
        controls.add(10, 0, 1);
        controls.add(0, 0, 3);
        controls.add(0, 0, -4);
        controls.add(5, 5, 7);
        controls.add(0, 0, 9);
        controls.finish();
        java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()));
        long[] got = new long[bytes.size() / 8];
        for (int i = 0; i < got.length; i++) {
            got[i] = in.readLong();
        }
        // The lead seek once, the later seeks added to the entry before them,
        // and the last entry's seek dropped.
        assertArrayEquals(new long[] {0, 0, 7, 10, 0, 0, 5, 5, 0}, got);
    }

    @Test
    public void cancellationStopsTheRebuild() throws Exception {
        byte[] old = randomBytes(new Random(15), 300_000);
        byte[] nw = randomBytes(new Random(16), 300_000);
        File patch = write("patch", UpdateDiff.diff(old, nw));
        try {
            UpdatePatch.apply(write("old", old), patch, tmp.newFile(),
                    UpdatePatch.toHex(UpdateDiff.sha256(old)), () -> true);
            fail("not cancelled");
        } catch (InterruptedIOException expected) {
            // stopped
        }
    }

    // ── the compressed APK ───────────────────────────────────────────────

    @Test
    public void unxzRoundTripsAndChecksEverything() throws Exception {
        byte[] data = randomBytes(new Random(17), 200_000);
        byte[] packed = xz(data);
        File out = tmp.newFile();
        String sha = UpdatePatch.unxz(write("a.xz", packed), out, data.length, null);
        assertArrayEquals(data, Files.readAllBytes(out.toPath()));
        assertEquals(UpdatePatch.toHex(UpdateDiff.sha256(data)), sha);

        assertUnxzRefused("larger than allowed", packed, data.length - 1);
        assertUnxzRefused("not xz", data, Long.MAX_VALUE);
        assertUnxzRefused("empty", new byte[0], Long.MAX_VALUE);
        for (int n = 1; n < packed.length; n += Math.max(1, packed.length / 50)) {
            assertUnxzRefused("truncated to " + n, Arrays.copyOf(packed, n), Long.MAX_VALUE);
        }
        byte[] flipped = packed.clone();
        flipped[packed.length / 2] ^= 1;
        assertUnxzRefused("flipped byte", flipped, Long.MAX_VALUE);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void assertRoundTrip(byte[] old, byte[] nw) throws Exception {
        byte[] patch = UpdateDiff.diff(old, nw);
        File out = tmp.newFile();
        UpdatePatch.apply(write("old", old), write("patch", patch), out,
                UpdatePatch.toHex(UpdateDiff.sha256(old)), null);
        assertArrayEquals(nw, Files.readAllBytes(out.toPath()));
    }

    private void assertRefused(String what, File oldFile, byte[] patch) throws IOException {
        assertApplyFails(oldFile, write("bad", patch), null, what);
    }

    private void assertApplyFails(File oldFile, File patch, String expectedOldSha) throws IOException {
        assertApplyFails(oldFile, patch, expectedOldSha, patch.getName());
    }

    private void assertApplyFails(File oldFile, File patch, String expectedOldSha, String what) throws IOException {
        File out = tmp.newFile();
        try {
            UpdatePatch.apply(oldFile, patch, out, expectedOldSha, null);
            fail(what + ": applied");
        } catch (UpdatePatch.PatchException expected) {
            // refused
        } catch (IOException | RuntimeException e) {
            fail(what + ": failed with " + e + " instead of a PatchException");
        }
    }

    private void assertCraftRefused(File oldFile, byte[] old, byte[] nw, long[][] triples,
                                    byte[] diff, byte[] extra) throws IOException {
        assertRefused(Arrays.deepToString(triples), oldFile, craft(old, nw, triples, diff, extra));
    }

    private void assertUnxzRefused(String what, byte[] data, long max) throws IOException {
        try {
            UpdatePatch.unxz(write("bad.xz", data), tmp.newFile(), max, null);
            fail(what + ": unpacked");
        } catch (UpdatePatch.PatchException expected) {
            // refused
        }
    }

    /** A patch from explicit control triples (a triple may be cut short). */
    private static byte[] craft(byte[] old, byte[] nw, long[][] triples, byte[] diff, byte[] extra)
            throws IOException {
        ByteArrayOutputStream ctrl = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(new XZOutputStream(ctrl, new LZMA2Options()))) {
            for (long[] t : triples) {
                for (long v : t) {
                    data.writeLong(v);
                }
            }
        }
        return assemble(old, nw, ctrl.toByteArray(), xz(diff), xz(extra));
    }

    private static byte[] assemble(byte[] old, byte[] nw, byte[] ctrl, byte[] diff, byte[] extra)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(out);
        header.write(UpdatePatch.MAGIC);
        header.writeLong(old.length);
        header.writeLong(nw.length);
        header.write(UpdateDiff.sha256(old));
        header.write(UpdateDiff.sha256(nw));
        header.writeLong(ctrl.length);
        header.writeLong(diff.length);
        header.writeLong(extra.length);
        header.write(ctrl);
        header.write(diff);
        header.write(extra);
        return out.toByteArray();
    }

    static byte[] xz(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XZOutputStream xz = new XZOutputStream(out, new LZMA2Options())) {
            xz.write(data);
        }
        return out.toByteArray();
    }

    /**
     * Rewrites the LZMA2 dictionary-size byte in the first block header of an
     * .xz stream (with the header's CRC32 recomputed), so the stream asks the
     * decoder for that dictionary: 30 = 128 MiB.
     */
    static byte[] withDictionaryByte(byte[] xz, int dictByte) {
        byte[] out = xz.clone();
        int headerStart = 12; // after the stream header
        int headerSize = ((out[headerStart] & 0xff) + 1) * 4;
        // Block header: size, flags (one filter, no sizes), filter id 0x21,
        // property size 1, the dictionary byte, padding, CRC32.
        assertEquals(0x00, out[headerStart + 1] & 0xff);
        assertEquals(0x21, out[headerStart + 2] & 0xff);
        out[headerStart + 4] = (byte) dictByte;
        CRC32 crc = new CRC32();
        crc.update(out, headerStart, headerSize - 4);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) {
            out[headerStart + headerSize - 4 + i] = (byte) (value >>> (8 * i));
        }
        return out;
    }

    private File write(String name, byte[] data) throws IOException {
        File f = File.createTempFile(name, ".bin", tmp.getRoot());
        Files.write(f.toPath(), data);
        return f;
    }

    private static byte[] randomBytes(Random random, int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] tail(byte[] b, int n) {
        return Arrays.copyOfRange(b, b.length - n, b.length);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static void putLong(byte[] b, int at, long v) {
        for (int i = 7; i >= 0; i--) {
            b[at + i] = (byte) v;
            v >>>= 8;
        }
    }
}
