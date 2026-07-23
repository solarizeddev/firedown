package com.solarized.firedown.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.sync.model.VaultEntry;

import java.util.Objects;

/**
 * Glide model for a backed-up cloud file, loaded by {@link VaultObjectModelLoader}
 * as a decrypt-on-read {@code InputStream} (so the Backups list / viewer can show
 * a real preview from the CLOUD — even after the local copy is deleted — instead
 * of falling back to a mime glyph). Carries only what the reader needs; the DEK
 * travels wrapped and is unwrapped on-device by the loader, never on the server.
 *
 * <p>The object bytes are immutable (an object id is server-random per upload and
 * its content never changes), so {@link #objectId} is a stable Glide cache key.</p>
 */
public final class VaultObjectModel {

    public final String objectId;
    public final String wrappedDek;
    public final long size;
    public final int chunkCount;

    public VaultObjectModel(String objectId, String wrappedDek, long size, int chunkCount) {
        this.objectId = objectId;
        this.wrappedDek = wrappedDek;
        this.size = size;
        this.chunkCount = chunkCount;
    }

    /** Builds a model from a manifest entry. */
    public static VaultObjectModel of(VaultEntry entry) {
        return new VaultObjectModel(entry.objectId, entry.wrappedDek, entry.size, entry.chunkCount);
    }

    /** Stable Glide cache key — the immutable object id. */
    public String cacheKey() {
        return objectId != null ? objectId : "";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VaultObjectModel)) {
            return false;
        }
        return Objects.equals(objectId, ((VaultObjectModel) o).objectId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(objectId);
    }

    @NonNull
    @Override
    public String toString() {
        return "VaultObjectModel{" + objectId + "}";
    }
}
