package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The saved WebDAV sign-in. It lives in the backup-EXCLUDED
 * {@code secret_shared_prefs} beside the recovery code, the password wrapped
 * under the same keystore key ({@link SyncSecrets#seal}), so neither a cloud
 * backup of the app's settings nor a raw prefs dump carries it. A restore
 * onto a new phone signs in again, as it enters the recovery code again.
 *
 * <p>Saving one does not by itself make the server THE backup place: the
 * stored kind does ({@link BackupStores#place}), which
 * {@code CloudBackupManager.adoptWebDav} sets with the save; choosing another
 * place {@link #clear}s this. {@link #generation()} changes on every save and
 * clear, so a cached store knows when it is stale without unwrapping the
 * password.
 */
public final class WebDavSettings {

    private static final String KEY_URL = "firedown.backup.webdav.url";
    private static final String KEY_USER = "firedown.backup.webdav.user";
    private static final String KEY_PASSWORD = "firedown.backup.webdav.password";
    private static final String KEY_PIN = "firedown.backup.webdav.pin";

    private static final AtomicInteger GENERATION = new AtomicInteger();

    private final SharedPreferences prefs;

    public WebDavSettings(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(SyncSecrets.PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a server is saved (cheap: no keystore). */
    public boolean isConfigured() {
        return prefs.contains(KEY_URL);
    }

    /** The saved address, for labels without the password (cheap). */
    @Nullable
    public String url() {
        return prefs.getString(KEY_URL, null);
    }

    /** The saved user name, to prefill a reconnect (cheap: no keystore). */
    @Nullable
    public String user() {
        return prefs.getString(KEY_USER, null);
    }

    /** The saved sign-in, or null when none is saved or the password can no
     *  longer be unwrapped (the keystore key is gone). */
    @Nullable
    public WebDavConfig load() {
        String url = prefs.getString(KEY_URL, null);
        String user = prefs.getString(KEY_USER, null);
        if (url == null || user == null) {
            return null;
        }
        byte[] password = SyncSecrets.open(prefs.getString(KEY_PASSWORD, null));
        if (password == null) {
            return null;
        }
        String pass = new String(password, StandardCharsets.UTF_8);
        Arrays.fill(password, (byte) 0);
        return new WebDavConfig(url, user, pass, prefs.getString(KEY_PIN, null));
    }

    /** Saves {@code config} as the backup place. {@code apply()} is enough:
     *  every reader shares this process's prefs instance, which changes at
     *  once; only the disk write is deferred. */
    public void save(@NonNull WebDavConfig config) {
        byte[] password = config.password.getBytes(StandardCharsets.UTF_8);
        String sealed = SyncSecrets.seal(password);
        Arrays.fill(password, (byte) 0);
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_URL, config.url)
                .putString(KEY_USER, config.user)
                .putString(KEY_PASSWORD, sealed);
        if (config.pinSha256 == null) {
            edit.remove(KEY_PIN);
        } else {
            edit.putString(KEY_PIN, config.pinSha256);
        }
        edit.apply();
        GENERATION.incrementAndGet();
    }

    /** Forgets the server. Nothing on it is touched. */
    public void clear() {
        if (!isConfigured()) {
            return;
        }
        prefs.edit().remove(KEY_URL).remove(KEY_USER).remove(KEY_PASSWORD).remove(KEY_PIN)
                .apply();
        GENERATION.incrementAndGet();
    }

    /** Changes on every {@link #save} and {@link #clear} in this process. */
    static int generation() {
        return GENERATION.get();
    }
}
