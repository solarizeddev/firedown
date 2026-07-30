package com.solarized.firedown.nwc;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.sync.SyncSecrets;

import java.nio.charset.StandardCharsets;

/**
 * At-rest storage for the user's Nostr Wallet Connect connection string.
 *
 * <p><b>This is a spending capability, and it is stored like one.</b> The
 * connection secret can move money inside whatever budget the wallet granted,
 * so it rides in {@link SyncSecrets}'s named-blob API: the backup-EXCLUDED
 * {@code secret_shared_prefs} file, wrapped with the AndroidKeyStore AES-256-GCM
 * key (hardware-backed where available). That means a prefs dump doesn't reveal
 * it, and — crucially — it never leaves the device via Auto Backup, so a cloud
 * restore onto a different phone can't carry the user's wallet with it.
 *
 * <p>Deliberately a thin facade rather than its own keystore plumbing: the
 * recovery code and the pending-purchase record already use exactly this
 * mechanism, and a second copy of GCM-wrap code is a second place to get the
 * IV handling wrong.
 *
 * <p>The stored value is the raw connection string. It is re-parsed on every
 * read rather than caching a parsed object, so a connection the wallet has
 * since revoked fails at use time (where the error is actionable) rather than
 * silently at load.
 */
public final class NwcWallet {

    /** Named blob key inside {@link SyncSecrets}. */
    private static final String BLOB_NAME = "nwc.connection.v1";

    private final SyncSecrets secrets;

    public NwcWallet(@NonNull Context context) {
        this.secrets = new SyncSecrets(context.getApplicationContext());
    }

    /** Whether a wallet is connected. Cheap — no parse, no crypto. */
    public boolean isConnected() {
        return secrets.getBlob(BLOB_NAME) != null;
    }

    /**
     * Persists a connection string. The caller must have PARSED it (and,
     * ideally, proved it works with {@code get_info}) first — storing an
     * unusable string only defers the failure to the payment.
     */
    public void store(@NonNull String connectionString) {
        secrets.putBlob(BLOB_NAME, connectionString.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The connected wallet, or null when none is stored or the stored value no
     * longer parses. Null on unreadable rather than throwing: a wallet is an
     * optional convenience, and the QR/copy payment path is always there.
     */
    @Nullable
    public NwcUri load() {
        byte[] raw = secrets.getBlob(BLOB_NAME);
        if (raw == null) {
            return null;
        }
        try {
            return NwcUri.parse(new String(raw, StandardCharsets.UTF_8));
        } catch (NwcUri.MalformedException e) {
            return null;
        } finally {
            SyncSecrets.wipe(raw);
        }
    }

    /**
     * A short label for the connected wallet ("me@getalby.com · relay.getalby.com"),
     * or null when nothing is connected. Never contains the secret.
     */
    @Nullable
    public String label() {
        NwcUri uri = load();
        return uri == null ? null : uri.displayLabel();
    }

    /** Forgets the wallet. Nothing is revoked server-side — that is the
     *  wallet's own screen — so the copy for this action says "disconnect",
     *  never "revoke". */
    public void disconnect() {
        secrets.removeBlob(BLOB_NAME);
    }
}
