package com.solarized.firedown.update;

import org.tukaani.xz.XZIOException;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Applies an update patch: rebuilds the new release's APK from the APK
 * installed on the phone. Pure Java on purpose — the release build compiles
 * this same file to check every patch before it is published, and
 * scripts/update-harness runs it on a plain JVM.
 *
 * <p>The format (all integers big-endian):
 * <pre>
 *   0   "FDPATCH1"              8-byte magic
 *   8   oldSize      int64      length of the APK the patch starts from
 *   16  newSize      int64      length of the APK it produces
 *   24  oldSha256    32 bytes
 *   56  newSha256    32 bytes
 *   88  ctrlLength   int64      compressed length of each of the three
 *   96  diffLength   int64      xz streams that follow, in this order
 *   104 extraLength  int64
 *   112 ctrl | diff | extra     the file ends exactly here
 * </pre>
 * The control stream is a list of (add, copy, seek) triples of int64. For
 * each one: {@code add} bytes are the old file's bytes from the current old
 * position plus the next {@code add} bytes of the diff stream (mod 256),
 * then {@code copy} bytes come straight from the extra stream, then the old
 * position moves by {@code seek}. These are bsdiff's semantics; the
 * generator (app/src/updatetool) is a port of bsdiff 4.3.
 *
 * <p>Untrusted input. Every length is checked before it is used, nothing is
 * allocated from a size the file names, and each triple must produce at
 * least one byte (only the first may be a pure seek), so the work is bounded
 * by the output size whatever the patch says. The xz decoders run under a
 * memory limit. The output is hashed as it is written and must equal the
 * header's newSha256; the caller still checks it against status.json.
 *
 * <p>Failures: {@link PatchException} means the patch (or the old file it
 * was given) is wrong — retrying the same patch cannot help. Any other
 * IOException is the device's (storage full, file unreadable) and may pass.
 */
public final class UpdatePatch {

    public static final byte[] MAGIC = "FDPATCH1".getBytes(StandardCharsets.US_ASCII);
    public static final int HEADER_SIZE = 112;

    /** Largest APK this code will build or read: far above any real release. */
    public static final long MAX_FILE_SIZE = 1L << 30;

    /**
     * xz decoder memory limit, in KiB, PER DECODER: {@link #apply} opens three
     * at once, so a patch can ask for three times this. The generator uses
     * 8 MiB dictionaries (~9 MiB of decoder each); 16 MiB keeps a patch under
     * ~50 MB of heap whatever it asks for.
     */
    public static final int XZ_MEMORY_LIMIT_KIB = 16 * 1024;

    private static final int CHUNK = 1 << 16;

    private UpdatePatch() {}

    /** The patch file or its base is wrong; the same inputs will fail again. */
    public static final class PatchException extends IOException {
        public PatchException(String message) {
            super(message);
        }

        public PatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The parsed header of a patch file. */
    public static final class Header {
        public final long oldSize;
        public final long newSize;
        public final byte[] oldSha256;
        public final byte[] newSha256;
        public final long ctrlLength;
        public final long diffLength;
        public final long extraLength;

        Header(long oldSize, long newSize, byte[] oldSha256, byte[] newSha256,
               long ctrlLength, long diffLength, long extraLength) {
            this.oldSize = oldSize;
            this.newSize = newSize;
            this.oldSha256 = oldSha256;
            this.newSha256 = newSha256;
            this.ctrlLength = ctrlLength;
            this.diffLength = diffLength;
            this.extraLength = extraLength;
        }

        public String oldSha256Hex() {
            return toHex(oldSha256);
        }

        public String newSha256Hex() {
            return toHex(newSha256);
        }
    }

    /** Reads and validates the header against the patch file's own length. */
    public static Header readHeader(File patch) throws IOException {
        long patchLength = patch.length();
        if (patchLength < HEADER_SIZE) {
            throw new PatchException("patch shorter than its header");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(patch), HEADER_SIZE))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new PatchException("not a Firedown update patch");
            }
            long oldSize = in.readLong();
            long newSize = in.readLong();
            byte[] oldSha = new byte[32];
            in.readFully(oldSha);
            byte[] newSha = new byte[32];
            in.readFully(newSha);
            long ctrl = in.readLong();
            long diff = in.readLong();
            long extra = in.readLong();
            if (oldSize < 0 || oldSize > MAX_FILE_SIZE || newSize < 0 || newSize > MAX_FILE_SIZE) {
                throw new PatchException("patch names an impossible file size");
            }
            if (ctrl < 0 || diff < 0 || extra < 0) {
                throw new PatchException("patch has a negative stream length");
            }
            long remaining = patchLength - HEADER_SIZE;
            if (ctrl > remaining || diff > remaining - ctrl || extra != remaining - ctrl - diff) {
                throw new PatchException("patch stream lengths do not add up to the file");
            }
            return new Header(oldSize, newSize, oldSha, newSha, ctrl, diff, extra);
        }
    }

    /**
     * Builds {@code out} from {@code oldFile} and {@code patch}.
     *
     * @param expectedOldSha256 hex SHA-256 the caller already knows the old
     *        file has (it picked the patch by it); the header must name the
     *        same file. Null hashes the old file here instead (a full read),
     *        so the header's base is always checked against something.
     * @param cancelled polled between chunks; true stops with an
     *        {@link InterruptedIOException}. May be null.
     * @return the hex SHA-256 of the file written (equal to the header's).
     */
    public static String apply(File oldFile, File patch, File out,
                               String expectedOldSha256,
                               BooleanSupplier cancelled) throws IOException {
        Header header = readHeader(patch);
        if (oldFile.length() != header.oldSize) {
            throw new PatchException("old file length " + oldFile.length()
                    + " is not the patch's " + header.oldSize);
        }
        String baseSha = expectedOldSha256 != null ? expectedOldSha256 : sha256Hex(oldFile, cancelled);
        if (!baseSha.equalsIgnoreCase(header.oldSha256Hex())) {
            throw new PatchException("patch was made for a different APK");
        }

        MessageDigest digest = sha256();
        try (RandomAccessFile old = new RandomAccessFile(oldFile, "r");
             InputStream ctrlIn = openStream(patch, HEADER_SIZE, header.ctrlLength);
             InputStream diffIn = openStream(patch, HEADER_SIZE + header.ctrlLength, header.diffLength);
             InputStream extraIn = openStream(patch,
                     HEADER_SIZE + header.ctrlLength + header.diffLength, header.extraLength);
             OutputStream sink = new BufferedOutputStream(new FileOutputStream(out), CHUNK)) {

            DataInputStream ctrl = new DataInputStream(ctrlIn);
            byte[] oldBuf = new byte[CHUNK];
            byte[] buf = new byte[CHUNK];
            long newPos = 0;
            long oldPos = 0;
            boolean first = true;

            while (newPos < header.newSize) {
                checkCancelled(cancelled);
                long add = readControl(ctrl);
                long copy = readControl(ctrl);
                long seek = readControl(ctrl);
                if (add < 0 || copy < 0) {
                    throw new PatchException("negative length in the control stream");
                }
                if (add == 0 && copy == 0 && !first) {
                    throw new PatchException("control entry makes no progress");
                }
                first = false;
                if (add > header.newSize - newPos) {
                    throw new PatchException("control entry writes past the end of the new file");
                }
                if (add > header.oldSize - oldPos) {
                    throw new PatchException("control entry reads past the end of the old file");
                }

                long left = add;
                while (left > 0) {
                    int n = (int) Math.min(left, CHUNK);
                    old.seek(oldPos);
                    old.readFully(oldBuf, 0, n);
                    readPatchFully(diffIn, buf, n);
                    for (int i = 0; i < n; i++) {
                        buf[i] = (byte) (buf[i] + oldBuf[i]);
                    }
                    sink.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    oldPos += n;
                    newPos += n;
                    left -= n;
                    checkCancelled(cancelled);
                }

                if (copy > header.newSize - newPos) {
                    throw new PatchException("control entry copies past the end of the new file");
                }
                left = copy;
                while (left > 0) {
                    int n = (int) Math.min(left, CHUNK);
                    readPatchFully(extraIn, buf, n);
                    sink.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    newPos += n;
                    left -= n;
                    checkCancelled(cancelled);
                }

                // Bounds of the old position are checked here and again
                // before each read, so an overflowing seek cannot wrap.
                if (seek < -oldPos || seek > header.oldSize - oldPos) {
                    throw new PatchException("control entry seeks outside the old file");
                }
                oldPos += seek;
            }

            expectEnd(ctrlIn, "control");
            expectEnd(diffIn, "diff");
            expectEnd(extraIn, "extra");
        }

        byte[] written = digest.digest();
        if (!MessageDigest.isEqual(written, header.newSha256)) {
            throw new PatchException("rebuilt file does not match the patch's checksum");
        }
        return toHex(written);
    }

    /**
     * Decompresses an .xz file to {@code out}, refusing more than
     * {@code maxSize} bytes, and returns the output's hex SHA-256.
     * Corrupt or truncated input is a {@link PatchException}.
     */
    public static String unxz(File in, File out, long maxSize,
                              BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream raw = new BufferedInputStream(new FileInputStream(in), CHUNK);
             OutputStream sink = new BufferedOutputStream(new FileOutputStream(out), CHUNK)) {
            InputStream xz;
            try {
                xz = new XZInputStream(raw, XZ_MEMORY_LIMIT_KIB);
            } catch (XZIOException | EOFException e) {
                throw new PatchException("not a valid .xz file", e);
            }
            byte[] buf = new byte[CHUNK];
            long total = 0;
            while (true) {
                checkCancelled(cancelled);
                int n;
                try {
                    n = xz.read(buf);
                } catch (XZIOException | EOFException e) {
                    throw new PatchException("corrupt .xz file", e);
                }
                if (n < 0) {
                    break;
                }
                total += n;
                if (total > maxSize) {
                    throw new PatchException(".xz file expands past " + maxSize + " bytes");
                }
                sink.write(buf, 0, n);
                digest.update(buf, 0, n);
            }
        }
        return toHex(digest.digest());
    }

    /** Hex SHA-256 of a whole file. */
    public static String sha256Hex(File file, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
                checkCancelled(cancelled);
            }
        }
        return toHex(digest.digest());
    }

    public static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0xf];
            out[i * 2 + 1] = digits[bytes[i] & 0xf];
        }
        return new String(out);
    }

    // ── internals ─────────────────────────────────────────────────────────

    private static InputStream openStream(File patch, long offset, long length) throws IOException {
        FileInputStream file = new FileInputStream(patch);
        try {
            file.getChannel().position(offset);
            InputStream bounded = new BoundedInputStream(new BufferedInputStream(file, CHUNK), length);
            try {
                return new XZInputStream(bounded, XZ_MEMORY_LIMIT_KIB);
            } catch (XZIOException | EOFException e) {
                throw new PatchException("patch stream is not valid xz", e);
            }
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private static long readControl(DataInputStream ctrl) throws IOException {
        try {
            return ctrl.readLong();
        } catch (XZIOException | EOFException e) {
            throw new PatchException("control stream ended early or is corrupt", e);
        }
    }

    private static void readPatchFully(InputStream in, byte[] buf, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n;
            try {
                n = in.read(buf, done, length - done);
            } catch (XZIOException | EOFException e) {
                throw new PatchException("patch data is corrupt", e);
            }
            if (n < 0) {
                throw new PatchException("patch data ended early");
            }
            done += n;
        }
    }

    /** Reading to the end also makes the xz decoder check the stream's index and checksum. */
    private static void expectEnd(InputStream in, String name) throws IOException {
        int next;
        try {
            next = in.read();
        } catch (XZIOException | EOFException e) {
            throw new PatchException(name + " stream is corrupt at its end", e);
        }
        if (next != -1) {
            throw new PatchException(name + " stream has data past the end of the new file");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new InterruptedIOException("update rebuild cancelled");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads at most {@code limit} bytes, so a stream can never run into the next one. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
