package com.solarized.firedown.geckoview;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which sites have already shown the "This video needs DRM" sheet. The sheet
 * opens once per site; after that a page of that site only gets the dot on
 * the address bar's shield, and the security sheet's row is the way to turn
 * DRM on.
 *
 * <p>Kept as truncated SHA-256 hashes of the host, never the host: the list
 * is a record of sites the user visited, and a hash answers "seen before?"
 * without being readable as one. It lives in {@code backup_local.xml}, which
 * Auto Backup excludes, and is capped at {@link #MAX_SITES}, oldest first out.
 * The same file holds whether the one-time shield tip was shown.</p>
 *
 * <p>A private tab's sites are kept in memory only, never on disk: they go
 * with the process, as private tabs do.</p>
 */
public final class DrmNoticeSites {

    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_SITES = "drm_notice_sites";
    private static final String KEY_TIP_SHOWN = "drm_shield_tip_shown";
    static final int MAX_SITES = 200;

    /** Private tabs' sites (hashes), this process only. Main thread. */
    private static final Set<String> sPrivateSites = new HashSet<>();

    private DrmNoticeSites() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    /** True when the sheet was already shown for {@code host}. */
    public static boolean wasShown(Context context, @Nullable String host, boolean privateTab) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (privateTab) {
            return sPrivateSites.contains(hash(host));
        }
        return read(prefs(context)).contains(hash(host));
    }

    /** Records that the sheet was shown for {@code host}. */
    public static void markShown(Context context, @Nullable String host, boolean privateTab) {
        if (host == null || host.isEmpty()) {
            return;
        }
        if (privateTab) {
            if (sPrivateSites.size() >= MAX_SITES) {
                sPrivateSites.clear();
            }
            sPrivateSites.add(hash(host));
            return;
        }
        SharedPreferences p = prefs(context);
        List<String> sites = read(p);
        String key = hash(host);
        sites.remove(key);
        sites.add(key);
        while (sites.size() > MAX_SITES) {
            sites.remove(0);
        }
        p.edit().putString(KEY_SITES, String.join(",", sites)).apply();
    }

    /** True the first time it is asked, false after: the shield tip shows once. */
    public static boolean claimShieldTip(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_TIP_SHOWN, false)) {
            return false;
        }
        p.edit().putBoolean(KEY_TIP_SHOWN, true).apply();
        return true;
    }

    private static List<String> read(SharedPreferences p) {
        String stored = p.getString(KEY_SITES, "");
        if (stored == null || stored.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(stored.split(",")));
    }

    static String hash(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on Android; keep a working answer anyway.
            return Integer.toHexString(normalized.hashCode());
        }
    }
}
