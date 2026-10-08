package com.solarized.firedown.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * A page favicon, loaded through {@link FaviconModelLoader} from Firedown's own
 * favicon store ({@code FaviconStore}), fetching only when it isn't stored.
 *
 * <p>Identity is the icon URL plus the store's cache version for it, so Glide's
 * memory cache drops a bitmap once the stored bytes change or the store is
 * cleared. The page URL (the fetch's Referer) and {@code persist} are not part of
 * it: one icon serves many pages, and whether a fetch may be stored doesn't change
 * the picture.
 */
public final class FaviconModel {

    final String iconUrl;
    @Nullable
    final String pageUrl;
    /** False on surfaces that can show a private tab: a fetched icon is then
     *  displayed but never written to the store. */
    final boolean persist;
    final String version;

    public FaviconModel(@NonNull String iconUrl, @Nullable String pageUrl, boolean persist,
                        @NonNull String version) {
        this.iconUrl = iconUrl;
        this.pageUrl = pageUrl;
        this.persist = persist;
        this.version = version;
    }

    String cacheKey() {
        return "favicon:" + version + ":" + iconUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FaviconModel)) {
            return false;
        }
        FaviconModel other = (FaviconModel) o;
        return iconUrl.equals(other.iconUrl) && version.equals(other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(iconUrl, version);
    }

    @NonNull
    @Override
    public String toString() {
        return "FaviconModel{" + iconUrl + ", v" + version + (persist ? "" : ", ephemeral") + "}";
    }
}
