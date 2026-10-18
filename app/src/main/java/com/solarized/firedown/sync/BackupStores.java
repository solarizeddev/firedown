package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.NetworkType;

import com.solarized.firedown.data.di.NetworkModule;

/**
 * Which {@link BackupStore} is THE backup place. The kind of place is STORED
 * ({@link Place}, one key in the backup-excluded {@code secret_shared_prefs}
 * beside the sign-ins it points at), never inferred from which settings
 * happen to exist: choosing one place forgets the others
 * ({@code CloudBackupManager.adoptFolder} / {@code adoptWebDav}) and records
 * the choice here, so there is never a question of which wins.
 *
 * <p><b>Migration.</b> Builds before the stored kind made a saved WebDAV
 * server the place and the picked folder the fallback. An install with no
 * stored kind is migrated on its first read to exactly that: webdav when a
 * server is saved, else folder. The key lives in the backup-EXCLUDED prefs on
 * purpose: a restore onto a new phone carries no sign-in, and with no stored
 * kind it migrates the same way an old install does, instead of naming a
 * place whose credentials stayed behind.
 *
 * <p>The network stores are cached for the process and rebuilt only when
 * their saved sign-in changes: each owns a client (connections, the trusted
 * certificate) and a decoded-sidecar cache, which every worker, the Backups
 * list and the stream player then share.
 */
public final class BackupStores {

    /** The kinds of place, with the value stored for each. */
    public enum Place {
        /** A folder picked through the system picker ({@link FolderStore}). */
        FOLDER("folder"),
        /** A WebDAV server the user signed in to ({@link WebDavStore}). */
        WEBDAV("webdav"),
        /** An S3-compatible bucket the user brought keys for ({@link S3Store}). */
        S3("s3");

        final String value;

        Place(String value) {
            this.value = value;
        }

        /** The kind a stored value names; null for none or an unknown one
         *  (a later build's kind after a downgrade), which re-migrates. */
        @Nullable
        static Place of(@Nullable String value) {
            if (value == null) {
                return null;
            }
            for (Place p : values()) {
                if (p.value.equals(value)) {
                    return p;
                }
            }
            return null;
        }
    }

    static final String KEY_PLACE = "firedown.backup.place";

    private static WebDavStore sWebDav;
    private static int sGeneration = -1;
    private static S3Store sS3;
    private static int sS3Generation = -1;

    private BackupStores() {
    }

    private static SharedPreferences secretPrefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SyncSecrets.PREFS, Context.MODE_PRIVATE);
    }

    /** The kind of place chosen, migrating an install that predates the
     *  stored kind (see the class doc). Cheap: prefs only. */
    @NonNull
    public static Place place(@NonNull Context context) {
        SharedPreferences prefs = secretPrefs(context);
        Place stored = Place.of(prefs.getString(KEY_PLACE, null));
        if (stored != null) {
            return stored;
        }
        Place migrated = new WebDavSettings(context).isConfigured() ? Place.WEBDAV : Place.FOLDER;
        prefs.edit().putString(KEY_PLACE, migrated.value).apply();
        return migrated;
    }

    /**
     * Makes {@code place} THE place: records the kind and forgets every OTHER
     * server's saved sign-in (the local state only, never what is on the
     * server or in the bucket). The caller has saved the chosen place's own
     * settings first, and forgets the picked folder itself when the place is
     * a server (the grant needs the content resolver; see
     * {@code CloudBackupManager}).
     */
    static void choose(@NonNull Context context, @NonNull Place place) {
        // The kind first: one prefs file, so its writes land in order, and a
        // process dying between them leaves the new place (saved before this
        // runs) with stale settings of another kind, never a kind whose
        // settings were just cleared.
        secretPrefs(context).edit().putString(KEY_PLACE, place.value).apply();
        if (place != Place.WEBDAV) {
            new WebDavSettings(context).clear();
        }
        if (place != Place.S3) {
            new S3Settings(context).clear();
        }
    }

    /** The active place. Cheap after the first call (no keystore). */
    @NonNull
    public static BackupStore open(@NonNull Context context, @NonNull SharedPreferences prefs) {
        return open(context, new FolderStore(context, prefs));
    }

    /** {@link #open(Context, SharedPreferences)} with a folder store the
     *  caller already holds. */
    @NonNull
    static BackupStore open(@NonNull Context context, @NonNull FolderStore folders) {
        switch (place(context)) {
            case WEBDAV:
                return webDav(context, folders);
            case S3:
                return s3(context, folders);
            case FOLDER:
            default:
                return folders;
        }
    }

    /** The saved bucket's store; the (unconfigured) folder store when none
     *  is saved or its keys can no longer be unwrapped, as for WebDAV. */
    @NonNull
    private static BackupStore s3(@NonNull Context context, @NonNull FolderStore folders) {
        S3Settings settings = new S3Settings(context);
        if (!settings.isConfigured()) {
            return folders;
        }
        synchronized (BackupStores.class) {
            int generation = S3Settings.generation();
            if (sS3 != null && generation == sS3Generation) {
                return sS3;
            }
            S3Config config = settings.load();
            if (config == null) {
                return folders;
            }
            sS3 = new S3Store(config, NetworkModule.globalClient);
            sS3Generation = generation;
            return sS3;
        }
    }

    /**
     * The saved WebDAV server's store. With none saved, or one saved but
     * unreadable (the keystore key is gone), there is no place that can be
     * used: the folder store is returned, and it is unconfigured, because the
     * folder was forgotten when the server was chosen. Reads as not set up.
     */
    @NonNull
    private static BackupStore webDav(@NonNull Context context, @NonNull FolderStore folders) {
        WebDavSettings settings = new WebDavSettings(context);
        if (!settings.isConfigured()) {
            return folders;
        }
        synchronized (BackupStores.class) {
            int generation = WebDavSettings.generation();
            if (sWebDav != null && generation == sGeneration) {
                return sWebDav;
            }
            WebDavConfig config = settings.load();
            if (config == null) {
                return folders;
            }
            sWebDav = new WebDavStore(config, NetworkModule.globalClient);
            sGeneration = generation;
            return sWebDav;
        }
    }

    /** Whether the active place is reached over the network. */
    public static boolean usesNetwork(@NonNull Context context) {
        return place(context) != Place.FOLDER;
    }

    /**
     * The WorkManager constraints of a backup or restore: a connection when
     * the place is a server; none for a folder, which may be on the phone or
     * a card (a provider that needs the network answers with an error the
     * worker retries).
     */
    @NonNull
    public static Constraints transferConstraints(@NonNull Context context) {
        Constraints.Builder b = new Constraints.Builder();
        if (usesNetwork(context)) {
            b.setRequiredNetworkType(NetworkType.CONNECTED);
        }
        return b.build();
    }
}
