package com.solarized.firedown.update;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Command line for the release build (app/release.gradle runs it in its own
 * JVM, so a diff's memory is not the Gradle daemon's) and for
 * scripts/update-harness. Release-build tool only: not part of the APK.
 *
 * <pre>
 *   diff   OLD NEW PATCH     make a patch, then apply it with the app's own
 *                            code and refuse to finish unless the result is
 *                            NEW byte for byte
 *   xz     IN OUT            compress, then decompress with the app's code
 *                            and refuse to finish unless it round-trips
 *   apply  OLD PATCH OUT     the app's applier, as the phone runs it
 *   unxz   IN OUT            the app's decompressor, as the phone runs it
 *   sha256 FILE
 * </pre>
 * Every output is written to a temporary name and renamed into place only
 * after its check, so a file that exists is a file that was checked. Exit
 * status 0 on success, 1 on any failure (with the reason on stderr).
 */
public final class UpdateTool {

    /** The suffix sort recurses; real APKs need far more than a default stack. */
    private static final long STACK_BYTES = 1L << 30;

    private UpdateTool() {}

    public static void main(String[] args) throws Exception {
        int[] status = {1};
        Thread worker = new Thread(null, () -> status[0] = run(args), "update-tool", STACK_BYTES);
        worker.start();
        worker.join();
        System.exit(status[0]);
    }

    static int run(String[] args) {
        try {
            if (args.length == 4 && args[0].equals("diff")) {
                diffChecked(new File(args[1]), new File(args[2]), new File(args[3]));
            } else if (args.length == 3 && args[0].equals("xz")) {
                xzChecked(new File(args[1]), new File(args[2]));
            } else if (args.length == 4 && args[0].equals("apply")) {
                System.out.println(UpdatePatch.apply(new File(args[1]), new File(args[2]),
                        new File(args[3]), null, null));
            } else if (args.length == 3 && args[0].equals("unxz")) {
                System.out.println(UpdatePatch.unxz(new File(args[1]), new File(args[2]),
                        UpdatePatch.MAX_FILE_SIZE, null));
            } else if (args.length == 2 && args[0].equals("sha256")) {
                System.out.println(UpdatePatch.sha256Hex(new File(args[1]), null));
            } else {
                System.err.println("usage: diff OLD NEW PATCH | xz IN OUT | apply OLD PATCH OUT | unxz IN OUT | sha256 FILE");
                return 1;
            }
            return 0;
        } catch (Throwable t) {
            System.err.println("update-tool " + String.join(" ", args) + ": " + t);
            return 1;
        }
    }

    static void diffChecked(File oldFile, File newFile, File patchFile) throws IOException {
        File tmp = new File(patchFile.getPath() + ".tmp");
        File check = new File(patchFile.getPath() + ".check");
        try {
            UpdateDiff.diff(oldFile, newFile, tmp);
            String rebuilt = UpdatePatch.apply(oldFile, tmp, check,
                    UpdatePatch.sha256Hex(oldFile, null), null);
            String expected = UpdatePatch.sha256Hex(newFile, null);
            if (!rebuilt.equals(expected)) {
                throw new IOException("patch rebuilds " + rebuilt + ", expected " + expected);
            }
            if (!sameBytes(check, newFile)) {
                throw new IOException("patch rebuilds different bytes with the same checksum");
            }
            Files.move(tmp.toPath(), patchFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            tmp.delete();
            check.delete();
        }
    }

    static void xzChecked(File in, File out) throws IOException {
        File tmp = new File(out.getPath() + ".tmp");
        File check = new File(out.getPath() + ".check");
        try {
            try (InputStream src = new BufferedInputStream(new FileInputStream(in), 1 << 16);
                 OutputStream dst = new XZOutputStream(
                         new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16),
                         new LZMA2Options(LZMA2Options.PRESET_DEFAULT))) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = src.read(buf)) > 0) {
                    dst.write(buf, 0, n);
                }
            }
            String unpacked = UpdatePatch.unxz(tmp, check, UpdatePatch.MAX_FILE_SIZE, null);
            String expected = UpdatePatch.sha256Hex(in, null);
            if (!unpacked.equals(expected)) {
                throw new IOException(".xz unpacks to " + unpacked + ", expected " + expected);
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            tmp.delete();
            check.delete();
        }
    }

    private static boolean sameBytes(File a, File b) throws IOException {
        if (a.length() != b.length()) {
            return false;
        }
        try (InputStream x = new BufferedInputStream(new FileInputStream(a), 1 << 16);
             InputStream y = new BufferedInputStream(new FileInputStream(b), 1 << 16)) {
            byte[] bx = new byte[1 << 16];
            byte[] by = new byte[1 << 16];
            while (true) {
                int nx = x.readNBytes(bx, 0, bx.length);
                int ny = y.readNBytes(by, 0, by.length);
                if (nx != ny) {
                    return false;
                }
                if (nx == 0) {
                    return true;
                }
                for (int i = 0; i < nx; i++) {
                    if (bx[i] != by[i]) {
                        return false;
                    }
                }
            }
        }
    }
}
