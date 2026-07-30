package com.solarized.firedown.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.sync.model.VaultEntry;

import java.util.Objects;

/**
 * Glide model for the STORED preview of a backed-up file — the base64 JPEG that
 * rides inside the encrypted Cloud Backup manifest ({@code VaultEntry.thumb}).
 *
 * <p>This exists so the Backups list can render that preview through Glide
 * instead of a hand-rolled cache. Before it, the list decoded the base64+JPEG
 * <em>on the main thread inside {@code onBindViewHolder}</em> and kept the
 * results in its own {@code LruCache} — a second memory budget sitting beside
 * Glide's own (which is device-sized) purely to amortise a decode Glide would
 * never have done on that thread.
 *
 * <p>{@link #objectId} is the cache key and it is a good one: server-random per
 * object and immutable for the object's life, so a key can never collide or go
 * stale — the same property {@link VaultObjectModel} relies on. Equality is on
 * the id alone for that reason; the payload is derived from it.
 */
public final class VaultThumbModel {

    /** Server-random, immutable — the Glide cache key. */
    @NonNull
    public final String objectId;
    /** The stored base64 JPEG. Never null (callers check first). */
    @NonNull
    public final String thumbBase64;

    public VaultThumbModel(@NonNull String objectId, @NonNull String thumbBase64) {
        this.objectId = objectId;
        this.thumbBase64 = thumbBase64;
    }

    /**
     * The model for this entry's stored preview, or null when it has none (a
     * pre-preview entry, or one whose generation failed) — the caller then falls
     * back to the local file or the mime glyph.
     */
    @Nullable
    public static VaultThumbModel of(@Nullable VaultEntry entry) {
        if (entry == null || entry.objectId == null || entry.thumb == null) {
            return null;
        }
        return new VaultThumbModel(entry.objectId, entry.thumb);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VaultThumbModel)) {
            return false;
        }
        return objectId.equals(((VaultThumbModel) o).objectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(objectId);
    }
}
