package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

import okhttp3.HttpUrl;

/**
 * An S3-compatible bucket the user brought: where it is, how to address it,
 * the keys, and the certificate the user chose to trust when the phone itself
 * does not ({@link #pinSha256}). Immutable; equality covers every field, so a
 * changed key or pin is a different config (and a fresh client — see
 * {@link BackupStores}).
 *
 * <p>{@link #endpoint} is scheme, host, port and an optional base path with no
 * trailing slash ({@code https://s3.us-west-004.backblazeb2.com}). The bucket
 * never sits in it: {@link #pathStyle} says whether it goes in the path
 * ({@code endpoint/bucket/key}, which every listed provider and a home MinIO
 * take) or the host ({@code bucket.host/key}). {@link #prefix} is "" or a key
 * prefix ending in "/", under which {@code Firedown Backup/} is made.
 */
public final class S3Config {

    /** How the bucket is addressed, as the user chose it on the sign-in
     *  page; {@link #AUTO} is resolved by the check into one of the others. */
    public enum Addressing {
        AUTO("auto"), PATH("path"), VIRTUAL("virtual");

        public final String value;

        Addressing(String value) {
            this.value = value;
        }

        @NonNull
        public static Addressing of(@Nullable String value) {
            for (Addressing a : values()) {
                if (a.value.equals(value)) {
                    return a;
                }
            }
            return AUTO;
        }
    }

    @NonNull
    public final String endpoint;
    @NonNull
    public final String region;
    @NonNull
    public final String bucket;
    @NonNull
    public final String prefix;
    @NonNull
    public final String accessKeyId;
    @NonNull
    public final String secret;
    /** Resolved: true = path-style, false = virtual-hosted. */
    public final boolean pathStyle;
    /** What the user chose (kept so a reconnect shows the same choice). */
    @NonNull
    public final Addressing addressing;
    /** Lowercase hex SHA-256 of the server's leaf certificate, trusted on
     *  top of the phone's own trust store; null when none was pinned. */
    @Nullable
    public final String pinSha256;

    public S3Config(@NonNull String endpoint, @NonNull String region, @NonNull String bucket,
                    @NonNull String prefix, @NonNull String accessKeyId, @NonNull String secret,
                    boolean pathStyle, @NonNull Addressing addressing,
                    @Nullable String pinSha256) {
        this.endpoint = endpoint;
        this.region = region;
        this.bucket = bucket;
        this.prefix = prefix;
        this.accessKeyId = accessKeyId;
        this.secret = secret;
        this.pathStyle = pathStyle;
        this.addressing = addressing;
        this.pinSha256 = pinSha256;
    }

    /** The parsed endpoint; never null for a config the check produced. */
    @NonNull
    HttpUrl endpointUrl() {
        HttpUrl url = HttpUrl.parse(endpoint);
        if (url == null) {
            throw new IllegalStateException("not an http(s) endpoint");
        }
        return url;
    }

    /** The same config at another region (the server named the right one). */
    @NonNull
    S3Config withRegion(@NonNull String region) {
        return new S3Config(endpoint, region, bucket, prefix, accessKeyId, secret, pathStyle,
                addressing, pinSha256);
    }

    /** The same config addressed the other way. */
    @NonNull
    S3Config withPathStyle(boolean pathStyle) {
        return new S3Config(endpoint, region, bucket, prefix, accessKeyId, secret, pathStyle,
                addressing, pinSha256);
    }

    /** The same config with a trusted certificate. */
    @NonNull
    S3Config withPin(@Nullable String pinSha256) {
        return new S3Config(endpoint, region, bucket, prefix, accessKeyId, secret, pathStyle,
                addressing, pinSha256 == null ? null : pinSha256.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof S3Config)) {
            return false;
        }
        S3Config other = (S3Config) o;
        return endpoint.equals(other.endpoint) && region.equals(other.region)
                && bucket.equals(other.bucket) && prefix.equals(other.prefix)
                && accessKeyId.equals(other.accessKeyId) && secret.equals(other.secret)
                && pathStyle == other.pathStyle && addressing == other.addressing
                && Objects.equals(pinSha256, other.pinSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoint, region, bucket, prefix, accessKeyId, secret, pathStyle,
                addressing, pinSha256);
    }

    /** No key in it, ever: this ends up in logs. */
    @NonNull
    @Override
    public String toString() {
        return "S3Config{" + endpoint + ", " + bucket + (prefix.isEmpty() ? "" : "/" + prefix)
                + ", " + region + (pathStyle ? ", path" : ", virtual")
                + (pinSha256 == null ? "" : ", pinned") + "}";
    }
}
