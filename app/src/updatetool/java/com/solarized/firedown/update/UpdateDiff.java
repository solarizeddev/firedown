package com.solarized.firedown.update;

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
import java.security.NoSuchAlgorithmException;

/**
 * Makes an update patch (the format is documented on {@link UpdatePatch}).
 * Release-build tool only: this directory is not part of the APK.
 *
 * <p>The algorithm is bsdiff 4.3 by Colin Percival (BSD 2-clause), ported
 * line for line: a Larsson–Sadakane suffix sort of the old file, then a
 * scan of the new file that extends approximate matches. Two changes from
 * the original: the diff and extra bytes are streamed into their xz
 * encoders instead of two newSize buffers, and a control entry that would
 * produce no bytes is folded into its neighbour (the applier refuses those,
 * which is what bounds its work).
 *
 * <p>Memory: the old and new files plus two int arrays the size of the old
 * file — about 10 bytes per old byte plus the new file. The release task
 * runs each diff in its own JVM with a heap sized for that. The suffix
 * sort recurses; {@link UpdateTool} runs it on a thread with a large stack.
 */
public final class UpdateDiff {

    private UpdateDiff() {}

    /** Writes the patch turning {@code oldFile} into {@code newFile} to {@code patchFile}. */
    public static void diff(File oldFile, File newFile, File patchFile) throws IOException {
        byte[] oldBytes = Files.readAllBytes(oldFile.toPath());
        byte[] newBytes = Files.readAllBytes(newFile.toPath());
        if (oldBytes.length > UpdatePatch.MAX_FILE_SIZE || newBytes.length > UpdatePatch.MAX_FILE_SIZE) {
            throw new IOException("file larger than " + UpdatePatch.MAX_FILE_SIZE + " bytes");
        }
        byte[] patch = diff(oldBytes, newBytes);
        try (OutputStream out = new FileOutputStream(patchFile)) {
            out.write(patch);
        }
    }

    /** The whole patch, in memory. */
    public static byte[] diff(byte[] oldBytes, byte[] newBytes) throws IOException {
        int[] index = suffixSort(oldBytes);

        ByteArrayOutputStream ctrlBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream diffBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream extraBytes = new ByteArrayOutputStream();
        Controls controls;
        try (XZOutputStream ctrlXz = new XZOutputStream(ctrlBytes, options());
             XZOutputStream diffXz = new XZOutputStream(diffBytes, options());
             XZOutputStream extraXz = new XZOutputStream(extraBytes, options())) {
            controls = new Controls(new DataOutputStream(ctrlXz));
            scan(oldBytes, newBytes, index, controls, diffXz, extraXz);
            controls.finish();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_GUESS + ctrlBytes.size()
                + diffBytes.size() + extraBytes.size());
        DataOutputStream header = new DataOutputStream(out);
        header.write(UpdatePatch.MAGIC);
        header.writeLong(oldBytes.length);
        header.writeLong(newBytes.length);
        header.write(sha256(oldBytes));
        header.write(sha256(newBytes));
        header.writeLong(ctrlBytes.size());
        header.writeLong(diffBytes.size());
        header.writeLong(extraBytes.size());
        header.flush();
        ctrlBytes.writeTo(out);
        diffBytes.writeTo(out);
        extraBytes.writeTo(out);
        return out.toByteArray();
    }

    private static final int HEADER_GUESS = UpdatePatch.HEADER_SIZE;

    /** 8 MiB dictionary (LZMA2 preset 6): the applier's memory limit allows 64. */
    private static LZMA2Options options() throws IOException {
        return new LZMA2Options(LZMA2Options.PRESET_DEFAULT);
    }

    // ── the scan (bsdiff.c main loop) ─────────────────────────────────────

    private static void scan(byte[] old, byte[] nw, int[] index, Controls controls,
                             OutputStream diffOut, OutputStream extraOut) throws IOException {
        int oldSize = old.length;
        int newSize = nw.length;
        int scan = 0;
        int len = 0;
        int lastScan = 0;
        int lastPos = 0;
        int lastOffset = 0;
        int[] pos = new int[1];
        byte[] chunk = new byte[1 << 16];

        while (scan < newSize) {
            int oldScore = 0;
            scan += len;
            int scsc = scan;
            for (; scan < newSize; scan++) {
                len = search(index, old, nw, scan, 0, oldSize, pos);
                for (; scsc < scan + len; scsc++) {
                    if (scsc + lastOffset < oldSize && old[scsc + lastOffset] == nw[scsc]) {
                        oldScore++;
                    }
                }
                if ((len == oldScore && len != 0) || len > oldScore + 8) {
                    break;
                }
                if (scan + lastOffset < oldSize && old[scan + lastOffset] == nw[scan]) {
                    oldScore--;
                }
            }

            if (len != oldScore || scan == newSize) {
                int s = 0;
                int sf = 0;
                int lenF = 0;
                for (int i = 0; lastScan + i < scan && lastPos + i < oldSize; ) {
                    if (old[lastPos + i] == nw[lastScan + i]) {
                        s++;
                    }
                    i++;
                    if (s * 2 - i > sf * 2 - lenF) {
                        sf = s;
                        lenF = i;
                    }
                }

                int lenB = 0;
                if (scan < newSize) {
                    s = 0;
                    int sb = 0;
                    for (int i = 1; scan >= lastScan + i && pos[0] >= i; i++) {
                        if (old[pos[0] - i] == nw[scan - i]) {
                            s++;
                        }
                        if (s * 2 - i > sb * 2 - lenB) {
                            sb = s;
                            lenB = i;
                        }
                    }
                }

                if (lastScan + lenF > scan - lenB) {
                    int overlap = (lastScan + lenF) - (scan - lenB);
                    s = 0;
                    int ss = 0;
                    int lenS = 0;
                    for (int i = 0; i < overlap; i++) {
                        if (nw[lastScan + lenF - overlap + i] == old[lastPos + lenF - overlap + i]) {
                            s++;
                        }
                        if (nw[scan - lenB + i] == old[pos[0] - lenB + i]) {
                            s--;
                        }
                        if (s > ss) {
                            ss = s;
                            lenS = i + 1;
                        }
                    }
                    lenF += lenS - overlap;
                    lenB -= lenS;
                }

                for (int done = 0; done < lenF; ) {
                    int n = Math.min(chunk.length, lenF - done);
                    for (int i = 0; i < n; i++) {
                        chunk[i] = (byte) (nw[lastScan + done + i] - old[lastPos + done + i]);
                    }
                    diffOut.write(chunk, 0, n);
                    done += n;
                }
                int extraLen = (scan - lenB) - (lastScan + lenF);
                extraOut.write(nw, lastScan + lenF, extraLen);

                controls.add(lenF, extraLen, (long) (pos[0] - lenB) - (lastPos + lenF));

                lastScan = scan - lenB;
                lastPos = pos[0] - lenB;
                lastOffset = pos[0] - scan;
            }
        }
    }

    /**
     * Folds no-progress entries so the applier's one-progress-per-entry rule
     * holds: a pure seek before the first real entry is written once, as the
     * one entry allowed to produce nothing; a pure seek after one is added to
     * that entry's seek. bsdiff emits them rarely (the first match, and an
     * overlap that trims the forward extension to nothing).
     */
    static final class Controls {
        private final DataOutputStream out;
        private long leadSeek;
        private boolean havePending;
        private long pendingAdd;
        private long pendingCopy;
        private long pendingSeek;
        private boolean wroteAny;

        Controls(DataOutputStream out) {
            this.out = out;
        }

        void add(long add, long copy, long seek) throws IOException {
            if (add == 0 && copy == 0) {
                if (havePending) {
                    pendingSeek += seek;
                } else {
                    leadSeek += seek;
                }
                return;
            }
            if (havePending) {
                write(pendingAdd, pendingCopy, pendingSeek);
            } else if (leadSeek != 0) {
                write(0, 0, leadSeek);
            }
            havePending = true;
            pendingAdd = add;
            pendingCopy = copy;
            pendingSeek = seek;
        }

        /** The last entry's seek moves past everything the new file needs: drop it. */
        void finish() throws IOException {
            if (havePending) {
                write(pendingAdd, pendingCopy, 0);
            }
            out.flush();
        }

        private void write(long add, long copy, long seek) throws IOException {
            if (wroteAny && add == 0 && copy == 0) {
                throw new IllegalStateException("no-progress control entry after the first");
            }
            out.writeLong(add);
            out.writeLong(copy);
            out.writeLong(seek);
            wroteAny = true;
        }
    }

    // ── suffix search (bsdiff.c search/matchlen) ──────────────────────────

    private static int matchLen(byte[] old, int oldStart, byte[] nw, int newStart) {
        int i = 0;
        int max = Math.min(old.length - oldStart, nw.length - newStart);
        while (i < max && old[oldStart + i] == nw[newStart + i]) {
            i++;
        }
        return i;
    }

    /** Unsigned memcmp of old[oldStart..] and new[newStart..] over their common length. */
    private static int compare(byte[] old, int oldStart, byte[] nw, int newStart) {
        int max = Math.min(old.length - oldStart, nw.length - newStart);
        for (int i = 0; i < max; i++) {
            int a = old[oldStart + i] & 0xff;
            int b = nw[newStart + i] & 0xff;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return 0;
    }

    private static int search(int[] index, byte[] old, byte[] nw, int newStart, int st, int en, int[] pos) {
        while (en - st >= 2) {
            int x = st + (en - st) / 2;
            if (compare(old, index[x], nw, newStart) < 0) {
                st = x;
            } else {
                en = x;
            }
        }
        int x = matchLen(old, index[st], nw, newStart);
        int y = matchLen(old, index[en], nw, newStart);
        if (x > y) {
            pos[0] = index[st];
            return x;
        }
        pos[0] = index[en];
        return y;
    }

    // ── Larsson–Sadakane suffix sort (bsdiff.c qsufsort/split) ────────────

    static int[] suffixSort(byte[] old) {
        int oldSize = old.length;
        int[] index = new int[oldSize + 1];
        int[] rank = new int[oldSize + 1];
        int[] buckets = new int[256];

        for (int i = 0; i < oldSize; i++) {
            buckets[old[i] & 0xff]++;
        }
        for (int i = 1; i < 256; i++) {
            buckets[i] += buckets[i - 1];
        }
        for (int i = 255; i > 0; i--) {
            buckets[i] = buckets[i - 1];
        }
        buckets[0] = 0;

        for (int i = 0; i < oldSize; i++) {
            index[++buckets[old[i] & 0xff]] = i;
        }
        index[0] = oldSize;
        for (int i = 0; i < oldSize; i++) {
            rank[i] = buckets[old[i] & 0xff];
        }
        rank[oldSize] = 0;
        for (int i = 1; i < 256; i++) {
            if (buckets[i] == buckets[i - 1] + 1) {
                index[buckets[i]] = -1;
            }
        }
        index[0] = -1;

        for (int h = 1; index[0] != -(oldSize + 1); h += h) {
            int len = 0;
            int i = 0;
            while (i < oldSize + 1) {
                if (index[i] < 0) {
                    len -= index[i];
                    i -= index[i];
                } else {
                    if (len != 0) {
                        index[i - len] = -len;
                    }
                    len = rank[index[i]] + 1 - i;
                    split(index, rank, i, len, h);
                    i += len;
                    len = 0;
                }
            }
            if (len != 0) {
                index[i - len] = -len;
            }
        }

        for (int i = 0; i < oldSize + 1; i++) {
            index[rank[i]] = i;
        }
        return index;
    }

    private static void split(int[] index, int[] rank, int start, int len, int h) {
        // The second recursive call of the original is a tail call: loop instead.
        while (true) {
            if (len < 16) {
                int j;
                for (int k = start; k < start + len; k += j) {
                    j = 1;
                    int x = rank[index[k] + h];
                    for (int i = 1; k + i < start + len; i++) {
                        if (rank[index[k + i] + h] < x) {
                            x = rank[index[k + i] + h];
                            j = 0;
                        }
                        if (rank[index[k + i] + h] == x) {
                            int tmp = index[k + j];
                            index[k + j] = index[k + i];
                            index[k + i] = tmp;
                            j++;
                        }
                    }
                    for (int i = 0; i < j; i++) {
                        rank[index[k + i]] = k + j - 1;
                    }
                    if (j == 1) {
                        index[k] = -1;
                    }
                }
                return;
            }

            int x = rank[index[start + len / 2] + h];
            int jj = 0;
            int kk = 0;
            for (int i = start; i < start + len; i++) {
                if (rank[index[i] + h] < x) {
                    jj++;
                }
                if (rank[index[i] + h] == x) {
                    kk++;
                }
            }
            jj += start;
            kk += jj;

            int i = start;
            int j = 0;
            int k = 0;
            while (i < jj) {
                if (rank[index[i] + h] < x) {
                    i++;
                } else if (rank[index[i] + h] == x) {
                    int tmp = index[i];
                    index[i] = index[jj + j];
                    index[jj + j] = tmp;
                    j++;
                } else {
                    int tmp = index[i];
                    index[i] = index[kk + k];
                    index[kk + k] = tmp;
                    k++;
                }
            }
            while (jj + j < kk) {
                if (rank[index[jj + j] + h] == x) {
                    j++;
                } else {
                    int tmp = index[jj + j];
                    index[jj + j] = index[kk + k];
                    index[kk + k] = tmp;
                    k++;
                }
            }

            if (jj > start) {
                split(index, rank, start, jj - start, h);
            }
            for (i = 0; i < kk - jj; i++) {
                rank[index[jj + i]] = kk - 1;
            }
            if (jj == kk - 1) {
                index[jj] = -1;
            }

            if (start + len > kk) {
                len = start + len - kk;
                start = kk;
            } else {
                return;
            }
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
