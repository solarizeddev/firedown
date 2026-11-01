package com.solarized.firedown.update;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/**
 * Randomized checks of the real UpdateDiff + UpdatePatch + UpdateTool, beyond
 * the JUnit cases: many random edits round-tripped, thousands of random
 * corruptions of a real patch (every one must be refused with a
 * PatchException — never applied, never another exception), random headers,
 * an APK-shaped file (large stored regions that move) for size and speed, and
 * the command line the release build runs. Exits 1 on any failure.
 *
 *   java UpdateFuzz WORKDIR [SEED]
 */
public final class UpdateFuzz {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261101L;
        Random random = new Random(seed);
        System.out.println("update-fuzz: seed " + seed);

        roundTrips(dir, random);
        corruptions(dir, random);
        randomHeaders(dir, random);
        apkShaped(dir, random);
        commandLine(dir, random);

        System.out.println("update-fuzz: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("ok   " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    // 300 random old/new pairs: random bytes, runs, edits, moves.
    private static void roundTrips(File dir, Random random) throws IOException {
        int bad = 0;
        String firstBad = null;
        for (int i = 0; i < 300; i++) {
            byte[] old = randomFile(random, random.nextInt(i < 50 ? 64 : 40_000));
            byte[] nw = edit(random, old);
            try {
                byte[] patch = UpdateDiff.diff(old, nw);
                File oldFile = write(dir, "rt-old", old);
                File out = new File(dir, "rt-out");
                UpdatePatch.apply(oldFile, write(dir, "rt-patch", patch), out,
                        UpdatePatch.toHex(UpdateDiff.sha256(old)), null);
                if (!Arrays.equals(nw, Files.readAllBytes(out.toPath()))) {
                    throw new IOException("different bytes");
                }
            } catch (Exception e) {
                bad++;
                if (firstBad == null) {
                    firstBad = "case " + i + ": " + e;
                }
            }
        }
        check(bad == 0, "300 random edits rebuild exactly" + (firstBad == null ? "" : " — " + bad + " failed, " + firstBad));
    }

    // 3000 random corruptions of one real patch: 1 to 8 bytes changed, a
    // span overwritten, or a span duplicated/removed.
    private static void corruptions(File dir, Random random) throws IOException {
        byte[] old = randomFile(random, 60_000);
        byte[] nw = edit(random, old);
        byte[] patch = UpdateDiff.diff(old, nw);
        File oldFile = write(dir, "c-old", old);
        int applied = 0;
        int wrong = 0;
        String example = null;
        File out = new File(dir, "c-out");
        for (int i = 0; i < 3000; i++) {
            byte[] bad = corrupt(random, patch);
            if (Arrays.equals(bad, patch)) {
                continue;
            }
            try {
                UpdatePatch.apply(oldFile, write(dir, "c-patch", bad), out, null, null);
                // Applied: only acceptable when it is still exactly the new file.
                applied++;
                if (!Arrays.equals(nw, Files.readAllBytes(out.toPath()))) {
                    wrong++;
                    example = "corruption #" + i + " applied to different bytes";
                }
            } catch (UpdatePatch.PatchException expected) {
                // refused, as it must be
            } catch (Throwable t) {
                wrong++;
                if (example == null) {
                    example = "corruption #" + i + " threw " + t;
                }
            }
        }
        check(wrong == 0 && applied < 30, "3000 random corruptions of a " + patch.length
                + "-byte patch: refused with a PatchException, or (" + applied
                + " of them) still exactly the new file" + (example == null ? "" : " — " + example));
    }

    // Valid magic, random everything else.
    private static void randomHeaders(File dir, Random random) throws IOException {
        byte[] old = randomFile(random, 1000);
        File oldFile = write(dir, "h-old", old);
        int wrong = 0;
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[UpdatePatch.HEADER_SIZE + random.nextInt(400)];
            random.nextBytes(junk);
            System.arraycopy(UpdatePatch.MAGIC, 0, junk, 0, UpdatePatch.MAGIC.length);
            if (random.nextBoolean()) {
                putLong(junk, 8, old.length);
            }
            try {
                UpdatePatch.apply(oldFile, write(dir, "h-patch", junk), new File(dir, "h-out"), null, null);
                wrong++;
            } catch (UpdatePatch.PatchException expected) {
                // refused
            } catch (Throwable t) {
                wrong++;
            }
        }
        check(wrong == 0, "500 random headers refused with a PatchException");
    }

    // An APK's shape: a large stored region (native libraries) that moves
    // between versions, smaller incompressible regions that change.
    private static void apkShaped(File dir, Random random) throws IOException {
        byte[] lib = randomFile(random, 12_000_000);
        byte[] dexOld = new byte[2_000_000];
        random.nextBytes(dexOld);
        byte[] dexNew = new byte[2_100_000];
        random.nextBytes(dexNew);
        byte[] res = randomFile(random, 1_500_000);
        byte[] resNew = res.clone();
        for (int i = 0; i < 2000; i++) {
            resNew[random.nextInt(resNew.length)] ^= 1;
        }
        byte[] old = concat(new byte[4096], dexOld, lib, res);
        byte[] nw = concat(new byte[4096], dexNew, lib, resNew);
        long start = System.nanoTime();
        byte[] patch = UpdateDiff.diff(old, nw);
        long diffMs = (System.nanoTime() - start) / 1_000_000;
        File out = new File(dir, "apk-out");
        start = System.nanoTime();
        UpdatePatch.apply(write(dir, "apk-old", old), write(dir, "apk-patch", patch), out, null, null);
        long applyMs = (System.nanoTime() - start) / 1_000_000;
        boolean same = Arrays.equals(nw, Files.readAllBytes(out.toPath()));
        // The changed dex dominates: the moved 12 MB library must cost ~nothing.
        boolean small = patch.length < dexNew.length + 400_000;
        check(same && small, String.format("APK-shaped %.1f MB → %.1f MB: patch %.2f MB (diff %d ms, apply %d ms)",
                old.length / 1e6, nw.length / 1e6, patch.length / 1e6, diffMs, applyMs));
    }

    // The tool exactly as app/release.gradle runs it.
    private static void commandLine(File dir, Random random) throws IOException {
        byte[] old = randomFile(random, 200_000);
        byte[] nw = edit(random, old);
        File oldFile = write(dir, "cli-old", old);
        File newFile = write(dir, "cli-new", nw);
        File patch = new File(dir, "cli.fdpatch");
        File xz = new File(dir, "cli.xz");
        check(UpdateTool.run(new String[] {"diff", oldFile.getPath(), newFile.getPath(), patch.getPath()}) == 0
                && patch.isFile() && !new File(patch.getPath() + ".tmp").exists()
                && !new File(patch.getPath() + ".check").exists(), "tool diff writes a checked patch and no temporaries");
        check(UpdateTool.run(new String[] {"xz", newFile.getPath(), xz.getPath()}) == 0 && xz.isFile(),
                "tool xz writes a checked .xz");
        File rebuilt = new File(dir, "cli-rebuilt");
        check(UpdateTool.run(new String[] {"apply", oldFile.getPath(), patch.getPath(), rebuilt.getPath()}) == 0
                && Arrays.equals(nw, Files.readAllBytes(rebuilt.toPath())), "tool apply rebuilds the new file");
        File unpacked = new File(dir, "cli-unpacked");
        check(UpdateTool.run(new String[] {"unxz", xz.getPath(), unpacked.getPath()}) == 0
                && Arrays.equals(nw, Files.readAllBytes(unpacked.toPath())), "tool unxz restores the file");
        byte[] bad = Files.readAllBytes(patch.toPath());
        bad[bad.length / 2] ^= 1;
        File badPatch = write(dir, "cli-bad", bad);
        check(UpdateTool.run(new String[] {"apply", oldFile.getPath(), badPatch.getPath(),
                new File(dir, "cli-x").getPath()}) == 1, "tool apply exits 1 on a corrupt patch");
        check(UpdateTool.run(new String[] {"diff", oldFile.getPath()}) == 1, "tool rejects bad usage");
        File missing = new File(dir, "does-not-exist");
        File none = new File(dir, "cli-none.fdpatch");
        check(UpdateTool.run(new String[] {"diff", missing.getPath(), newFile.getPath(), none.getPath()}) == 1
                && !none.exists(), "tool diff leaves no patch behind when it fails");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Random bytes with some structure: runs, repeats and text-like spans. */
    private static byte[] randomFile(Random random, int size) {
        byte[] b = new byte[size];
        int i = 0;
        while (i < size) {
            int len = Math.min(size - i, 1 + random.nextInt(2000));
            switch (random.nextInt(4)) {
                case 0:
                    Arrays.fill(b, i, i + len, (byte) random.nextInt(256));
                    break;
                case 1:
                    for (int j = 0; j < len; j++) {
                        b[i + j] = (byte) ('a' + random.nextInt(26));
                    }
                    break;
                case 2:
                    if (i > 0) {
                        int from = random.nextInt(i);
                        for (int j = 0; j < len; j++) {
                            b[i + j] = b[from + (j % Math.max(1, i - from))];
                        }
                        break;
                    }
                    // fall through
                default:
                    for (int j = 0; j < len; j++) {
                        b[i + j] = (byte) random.nextInt(256);
                    }
            }
            i += len;
        }
        return b;
    }

    /** A new version: random inserts, deletes, overwrites and moves. */
    private static byte[] edit(Random random, byte[] old) {
        byte[] b = old.clone();
        int edits = random.nextInt(8);
        for (int e = 0; e < edits; e++) {
            int at = b.length == 0 ? 0 : random.nextInt(b.length + 1);
            int len = random.nextInt(3000);
            switch (random.nextInt(4)) {
                case 0:
                    b = concat(Arrays.copyOfRange(b, 0, at), randomFile(random, len),
                            Arrays.copyOfRange(b, at, b.length));
                    break;
                case 1:
                    b = concat(Arrays.copyOfRange(b, 0, at), Arrays.copyOfRange(b, Math.min(b.length, at + len), b.length));
                    break;
                case 2:
                    for (int i = at; i < Math.min(b.length, at + len); i++) {
                        if (random.nextInt(4) == 0) {
                            b[i] ^= (byte) (1 + random.nextInt(255));
                        }
                    }
                    break;
                default:
                    if (b.length > 2) {
                        int from = random.nextInt(b.length);
                        int to = Math.min(b.length, from + len);
                        b = concat(Arrays.copyOfRange(b, to, b.length), Arrays.copyOfRange(b, from, to),
                                Arrays.copyOfRange(b, 0, from));
                    }
            }
        }
        return b;
    }

    private static byte[] corrupt(Random random, byte[] patch) {
        byte[] b = patch.clone();
        switch (random.nextInt(4)) {
            case 0: {
                int n = 1 + random.nextInt(8);
                for (int i = 0; i < n; i++) {
                    b[random.nextInt(b.length)] ^= (byte) (1 + random.nextInt(255));
                }
                return b;
            }
            case 1: {
                int at = random.nextInt(b.length);
                int len = Math.min(b.length - at, 1 + random.nextInt(64));
                for (int i = at; i < at + len; i++) {
                    b[i] = (byte) random.nextInt(256);
                }
                return b;
            }
            case 2: {
                int at = random.nextInt(b.length);
                int len = Math.min(b.length - at, 1 + random.nextInt(64));
                return concat(Arrays.copyOfRange(b, 0, at + len), Arrays.copyOfRange(b, at, b.length));
            }
            default: {
                int at = random.nextInt(b.length);
                int len = Math.min(b.length - at, 1 + random.nextInt(64));
                return concat(Arrays.copyOfRange(b, 0, at), Arrays.copyOfRange(b, at + len, b.length));
            }
        }
    }

    private static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
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
