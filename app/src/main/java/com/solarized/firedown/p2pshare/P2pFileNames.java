package com.solarized.firedown.p2pshare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the on-disk name for a received file. Pure Java so
 * {@code scripts/p2p-harness} drives the real thing on a plain JVM.
 *
 * <p>It replaced a loop over {@code UrlParser.parseFilePath} that never
 * terminated for a name without an extension: that helper only knows how to
 * put {@code (n)} in front of a dot, so for {@code notes} (or the download
 * directory itself, which an empty name resolves to) it returned the same
 * path forever while the path stayed taken. It ran on the shared disk
 * executor, so every Room write in the app stopped behind it. Here the
 * number goes before a plausible extension or at the end, and the search is
 * bounded.
 */
final class P2pFileNames {

    private P2pFileNames() {
    }

    /** Used when the offer names no file at all (or only characters the sanitizer drops). */
    static final String FALLBACK_NAME = "received-file";

    /** Every in-progress receive ends in this; the stale-partial prune matches it. */
    static final String PART_SUFFIX = ".part";

    /**
     * The file a receive writes into until the last byte has arrived and been
     * verified: {@code .<target>.part}, hidden. It sits in the public download
     * folder, so a file manager or a player app would otherwise list it while
     * it grows, and a video opened from there stalls where the received bytes
     * end. File managers hide a dot-file and MediaStore records it as
     * non-media; it is renamed to the target name only once complete.
     *
     * <p>A file name is at most {@link #MAX_NAME_BYTES} bytes, and the target
     * may already use all of them (the sanitizer's own cap), so the target part
     * is shortened by whole code points until the partial's name fits. The
     * result depends on the target name only, so a resume finds the same file.
     */
    @NonNull
    static File partFile(@NonNull File target) {
        String name = target.getName();
        int budget = MAX_NAME_BYTES - 1 - PART_SUFFIX.length();
        while (utf8Length(name) > budget) {
            name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        }
        return new File(target.getParentFile(), "." + name + PART_SUFFIX);
    }

    /** The longest file name, in bytes, the filesystems the app writes to accept. */
    static final int MAX_NAME_BYTES = 255;

    private static int utf8Length(@NonNull String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Where builds before the hidden name kept a partial ({@code <target>.part}):
     * read once, to carry a kept partial over to {@link #partFile} so the
     * resume still finds it.
     */
    @NonNull
    static File legacyPartFile(@NonNull File target) {
        return new File(target.getParentFile(), target.getName() + PART_SUFFIX);
    }

    /** Numbered candidates tried before a random suffix takes over. */
    static final int MAX_NUMBERED = 9999;

    /** A trailing "(n)" the base may already carry — counting continues from it. */
    private static final Pattern NUMBERED = Pattern.compile("^(.*)\\((\\d{1,9})\\)$");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Whether a candidate path is already used (on disk, or owned by a download row). */
    interface Taken {
        boolean test(@NonNull File candidate);
    }

    /** The name to use for {@code name}: itself, or {@link #FALLBACK_NAME} when blank. */
    @NonNull
    static String orFallback(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return FALLBACK_NAME;
        }
        return name;
    }

    /**
     * Index of the dot that starts {@code name}'s extension, or -1 when it has
     * none worth keeping: 1–8 letters or digits after the last dot, and the dot
     * is not the first character (a dot-file has no extension).
     */
    static int extensionDot(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return -1;
        }
        int length = name.length() - dot - 1;
        if (length > 8) {
            return -1;
        }
        for (int i = dot + 1; i < name.length(); i++) {
            if (!Character.isLetterOrDigit(name.charAt(i))) {
                return -1;
            }
        }
        return dot;
    }

    /**
     * A free file in {@code dir} for {@code name}: the name itself when free,
     * else {@code base(n).ext} counting up (from a number the base already
     * ends in, the download pipeline's own spelling), else a random suffix.
     * Always returns; never loops unbounded.
     */
    @NonNull
    static File uniquify(@NonNull File dir, @Nullable String name, @NonNull Taken taken) {
        String safe = orFallback(name);
        File candidate = new File(dir, safe);
        if (!taken.test(candidate)) {
            return candidate;
        }
        int dot = extensionDot(safe);
        String base = dot < 0 ? safe : safe.substring(0, dot);
        String ext = dot < 0 ? "" : safe.substring(dot);
        long start = 1;
        Matcher numbered = NUMBERED.matcher(base);
        if (numbered.matches()) {
            base = numbered.group(1);
            start = Long.parseLong(numbered.group(2)) + 1;
        }
        for (long n = start; n < start + MAX_NUMBERED; n++) {
            candidate = new File(dir, base + "(" + n + ")" + ext);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        // A directory with ten thousand same-named files: stop counting.
        // Random 32-bit suffixes collide with negligible odds; the last
        // candidate is returned even if taken rather than spinning.
        for (int attempt = 0; attempt < 16; attempt++) {
            candidate = new File(dir, base + "-" + randomSuffix() + ext);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return candidate;
    }

    private static String randomSuffix() {
        return String.format(Locale.US, "%08x", RANDOM.nextInt());
    }
}
