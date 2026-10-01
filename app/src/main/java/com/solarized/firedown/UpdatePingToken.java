package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The MONTHLY-ROTATING client token the update ping carries
 * ({@code X-App-Client}), so the website's ping statistics can count distinct
 * installs instead of distinct IP addresses.
 *
 * <p>Why a token at all: {@code firedown_stats.py} (firedown-stats repo)
 * estimates DAU/MAU from {@code /status.json} hits by hashing the client IP
 * under a per-month salt. IP uniquing is wrong in both directions — carrier
 * NAT folds many phones into one address, mobile IP churn splits one phone
 * into many — so the dashboard can only bracket the truth between the peak
 * day and the inflated month figure. A per-install token makes both counts
 * exact.
 *
 * <p>Why NOT a persistent install id: a stable identifier arriving daily with
 * an IP, a version and a country is a tracking beacon, and the stats
 * pipeline's privacy design (a per-month server salt that is DELETED, so
 * stored hashes can never be joined across months) exists precisely to
 * prevent that linkage. So the token is {@code HMAC-SHA256(secret, "YYYY-MM")}
 * truncated: stable within one calendar month (exact DAU and MAU), unrelated
 * between months (the server sees the same install as a different token every
 * month and cannot join them — the {@code secret} never leaves the device,
 * and it is the only thing that could). That is the SAME linkability the IP
 * hash already allows today, moved client-side; it does not widen it.
 *
 * <p>The secret lives in {@code backup_local.xml}, the install-local prefs
 * file EXCLUDED from Auto Backup (see {@code DownloadBackupMirror}): a
 * restore onto a new phone mints a new secret and counts as a new install,
 * which is the honest answer, rather than cloning one id across two devices.
 * The month is computed in UTC — the server buckets days in UTC too, so a
 * ping at 00:30 on the 1st lands in the same month on both sides.
 *
 * <p>Sent ONLY on the firedown.app origin request, never to the GitHub Raw
 * fallback (those pings are not counted and GitHub needs nothing from us).
 * Disclosed in the website's privacy page beside the update check — the
 * honest-copy rule: if what the ping carries changes, that copy changes too.
 */
public final class UpdatePingToken {

    /** Request header carrying the token. Logged by nginx as a column. */
    public static final String HEADER = "X-App-Client";

    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_SECRET = "update_ping_secret";
    private static final int SECRET_BYTES = 32;
    /** 16 base64url chars = 96 bits: plenty for uniquing, useless for lookup. */
    private static final int TOKEN_CHARS = 16;

    private UpdatePingToken() {
    }

    /**
     * The token for the current UTC month, minting the install secret on
     * first use. Returns null only if the platform lacks HMAC-SHA256, which
     * no supported Android does — the caller then sends no header and the
     * server falls back to IP uniquing for that ping.
     */
    @Nullable
    public static String current(@NonNull Context context) {
        byte[] secret = secret(context);
        return tokenFor(secret, currentMonthUtc());
    }

    /**
     * Pure derivation: {@code base64url(HMAC-SHA256(secret, month))[:16]}.
     * Package-visible so a JVM test can pin the shape without a Context.
     */
    @VisibleForTesting
    @Nullable
    static String tokenFor(@NonNull byte[] secret, @NonNull String month) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(month.getBytes(StandardCharsets.US_ASCII));
            String encoded = Base64.encodeToString(digest,
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return encoded.substring(0, TOKEN_CHARS);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    @VisibleForTesting
    @NonNull
    static String currentMonthUtc() {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return String.format(Locale.ROOT, "%04d-%02d",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
    }

    @NonNull
    private static byte[] secret(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_SECRET, null);
        if (stored != null) {
            byte[] decoded = Base64.decode(stored, Base64.NO_WRAP);
            if (decoded.length == SECRET_BYTES) {
                return decoded;
            }
        }
        byte[] fresh = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(fresh);
        prefs.edit().putString(KEY_SECRET, Base64.encodeToString(fresh, Base64.NO_WRAP)).apply();
        return fresh;
    }
}
