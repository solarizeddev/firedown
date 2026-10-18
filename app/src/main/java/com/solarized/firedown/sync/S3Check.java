package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Tests an S3 bucket BEFORE it is saved: the endpoint the user typed, the
 * keys, the region, the addressing, and the operations a backup needs.
 * Nothing is stored here; an {@link Kind#OK} result carries the
 * {@link S3Config} to save, with the region and addressing it resolved.
 *
 * <p><b>The endpoint.</b> A bare host gets {@code https://}; a query, a
 * fragment and a trailing slash are dropped; an endpoint that already starts
 * with the bucket ({@code backups.s3.example.com}, a virtual-hosted URL copied
 * from a console) is reduced to the provider's host and tried virtual-hosted
 * first. An empty region is guessed from the known endpoints
 * ({@link #guessRegion}), else {@code us-east-1}.
 *
 * <p><b>Addressing.</b> {@link S3Config.Addressing#AUTO} tries path-style
 * first: every listed provider takes it, and a home MinIO has no per-bucket
 * DNS or wildcard certificate. When that answers "no such bucket" or names no
 * usable region, virtual-hosted is tried next (bucket names that are valid
 * host names only, never for an IP address or localhost) and the style that
 * works is saved. AWS itself prefers virtual-hosted, so for an
 * {@code amazonaws.com} endpoint the order is reversed. An explicit choice
 * tries only that style.
 *
 * <p><b>The region.</b> A server that says the bucket is in another region
 * (S3's {@code AuthorizationHeaderMalformed} / {@code PermanentRedirect},
 * MinIO's {@code InvalidRegion}) is followed ONCE, and the region it named is
 * what gets saved.
 *
 * <p><b>The write check</b> mirrors {@link WebDavCheck}: under
 * {@code <prefix>Firedown Backup/} it writes a small object, reads it back,
 * and deletes it: the operations a backup, a restore and a removal need, with
 * keys that may be limited to the bucket or the prefix. Listing comes first,
 * because a key that cannot list cannot show what was backed up.
 *
 * <p>Off the main thread only: every step is a request.
 */
public final class S3Check {

    public enum Kind {
        /** Reached, signed in, writable: {@link Result#config} is ready to save. */
        OK,
        /** Not an address at all. */
        BAD_ADDRESS,
        /** Something answers, but not as S3. */
        NOT_S3,
        /** No answer: wrong host, offline, a timeout. */
        UNREACHABLE,
        /** The server's certificate is not one the phone (or a pin) accepts;
         *  {@link Result#cert} is what to show before trusting it. */
        UNTRUSTED_CERT,
        /** The access key id is unknown or the secret is wrong. */
        AUTH_FAILED,
        /** The bucket does not exist at this endpoint. */
        NO_SUCH_BUCKET,
        /** The keys may not list, write, read or delete under the prefix. */
        ACCESS_DENIED,
        /** The bucket is in another region and the server did not say which
         *  (when it says, the check follows it). */
        WRONG_REGION,
        /** The account is out of space (a storage cap, a quota). */
        FULL,
    }

    public static final class Result {
        @NonNull
        public final Kind kind;
        @Nullable
        public final S3Config config;
        @Nullable
        public final ServerTrust.CertInfo cert;

        Result(@NonNull Kind kind, @Nullable S3Config config, @Nullable ServerTrust.CertInfo cert) {
            this.kind = kind;
            this.config = config;
            this.cert = cert;
        }

        static Result of(Kind kind) {
            return new Result(kind, null, null);
        }
    }

    private static final byte[] WRITE_CHECK_BYTES =
            "firedown write check".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern DNS_BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private S3Check() {
    }

    /**
     * {@link #run} for a reconnect from the sign-in page: a null
     * {@code secret} means the saved one, and a null {@code pinSha256} the
     * certificate already trusted for the same endpoint. The secret comes
     * from {@code saved} only when it names the same endpoint (scheme, host
     * and port), the same bucket AND the same access key id, so a saved
     * secret is never sent anywhere else or paired with another key. A null
     * secret with nothing to reuse answers {@link Kind#AUTH_FAILED}.
     */
    @NonNull
    public static Result runReusing(@Nullable OkHttpClient base, @Nullable S3Config saved,
                                    @NonNull String endpoint, @NonNull String region,
                                    @NonNull String bucket, @NonNull String prefix,
                                    @NonNull String accessKeyId, @Nullable String secret,
                                    @NonNull S3Config.Addressing addressing,
                                    @Nullable String pinSha256) {
        HttpUrl typed = normalizeEndpoint(endpoint);
        if (typed == null) {
            return Result.of(Kind.BAD_ADDRESS);
        }
        boolean same = saved != null && WebDavCheck.sameServer(typed, HttpUrl.parse(saved.endpoint));
        String key = secret;
        if (key == null) {
            boolean sameKeys = same && saved.bucket.equals(bucket.trim())
                    && saved.accessKeyId.equals(accessKeyId.trim());
            if (!sameKeys) {
                return Result.of(Kind.AUTH_FAILED);
            }
            key = saved.secret;
        }
        String pin = pinSha256;
        if (pin == null && same) {
            pin = saved.pinSha256;
        }
        return run(base, endpoint, region, bucket, prefix, accessKeyId, key, addressing, pin);
    }

    /** Runs the whole check. {@code pinSha256} is a leaf certificate the user
     *  chose to trust after an {@link Kind#UNTRUSTED_CERT} answer (null the
     *  first time). */
    @NonNull
    public static Result run(@Nullable OkHttpClient base, @NonNull String endpoint,
                             @NonNull String region, @NonNull String bucket,
                             @NonNull String prefix, @NonNull String accessKeyId,
                             @NonNull String secret, @NonNull S3Config.Addressing addressing,
                             @Nullable String pinSha256) {
        HttpUrl typed = normalizeEndpoint(endpoint);
        String name = bucket.trim();
        if (typed == null) {
            return Result.of(Kind.BAD_ADDRESS);
        }
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf(' ') >= 0) {
            return Result.of(Kind.NO_SUCH_BUCKET);
        }
        boolean bucketInHost = typed.host().toLowerCase(Locale.ROOT)
                .startsWith(name.toLowerCase(Locale.ROOT) + ".");
        if (bucketInHost) {
            typed = typed.newBuilder().host(typed.host().substring(name.length() + 1)).build();
        }
        String endpointText = endpointString(typed);
        String reg = region.trim();
        if (reg.isEmpty()) {
            reg = guessRegion(typed.host());
        }
        String pin = pinSha256 == null ? null : pinSha256.toLowerCase(Locale.ROOT);
        S3Config base0 = new S3Config(endpointText, reg, name, normalizePrefix(prefix),
                accessKeyId.trim(), secret, true, addressing, pin);

        List<Boolean> styles = new ArrayList<>(2);
        boolean virtualPossible = DNS_BUCKET.matcher(name).matches() && !isIpOrLocal(typed.host());
        switch (addressing) {
            case PATH:
                styles.add(true);
                break;
            case VIRTUAL:
                styles.add(false);
                break;
            case AUTO:
            default:
                boolean virtualFirst = virtualPossible && (bucketInHost
                        || typed.host().toLowerCase(Locale.ROOT).endsWith("amazonaws.com"));
                styles.add(!virtualFirst);
                if (virtualPossible) {
                    styles.add(virtualFirst);
                }
                break;
        }
        Result first = null;
        for (boolean pathStyle : styles) {
            Result r = probe(base, base0.withPathStyle(pathStyle));
            if (r.kind == Kind.OK) {
                return r;
            }
            if (first == null) {
                first = r;
            }
            boolean tryOther = r.kind == Kind.NO_SUCH_BUCKET || r.kind == Kind.WRONG_REGION
                    || r.kind == Kind.NOT_S3;
            if (!tryOther) {
                return first;
            }
        }
        return first;
    }

    /** One addressing style: list, then write / read / delete. A wrong
     *  region the server names is followed ONCE: the check runs again there,
     *  and a second "wrong region" is final. */
    private static Result probe(@Nullable OkHttpClient base, S3Config config) {
        S3Client s3 = new S3Client(base, config);
        IOException e = writeCheck(s3);
        if (e == null) {
            return new Result(Kind.OK, config, null);
        }
        String named = e instanceof S3Client.RefusedException
                && ((S3Client.RefusedException) e).refusal == S3Client.Refusal.REGION
                ? ((S3Client.RefusedException) e).region : null;
        if (named == null || named.isEmpty() || named.equals(config.region)) {
            return failure(s3, e);
        }
        S3Config moved = config.withRegion(named);
        S3Client there = new S3Client(base, moved);
        IOException again = writeCheck(there);
        if (again == null) {
            return new Result(Kind.OK, moved, null);
        }
        return failure(there, again);
    }

    /** The bytes read back are not the bytes written: not an S3 store. */
    private static final class Mismatch extends IOException {
        Mismatch() {
            super("the test file read back differently");
        }
    }

    /** Null when the place works, else what failed. */
    @Nullable
    private static IOException writeCheck(S3Client s3) {
        String root = s3.config().prefix + BackupLayout.ROOT_DIR + "/";
        try {
            s3.listObjects(root, null, 1, "list the bucket");
        } catch (IOException e) {
            return e;
        }
        String probe = root + "write-check-" + BackupLayout.newObjectId().substring(0, 8) + ".tmp";
        boolean written = false;
        try {
            s3.putObject(probe, WRITE_CHECK_BYTES, 0, WRITE_CHECK_BYTES.length, "write a test file");
            written = true;
            byte[] back = s3.getObjectBytes(probe, 1024, "read the test file");
            if (back == null || !Arrays.equals(back, WRITE_CHECK_BYTES)) {
                return new Mismatch();
            }
            s3.deleteObject(probe, "delete the test file");
            written = false;
            return null;
        } catch (IOException e) {
            return e;
        } finally {
            if (written) {
                try {
                    s3.deleteObject(probe, "delete the test file");
                } catch (IOException | RuntimeException ignored) {
                    // best-effort: one small file is all it leaves
                }
            }
        }
    }

    private static Result failure(S3Client s3, IOException e) {
        ServerTrust.CertInfo cert = s3.rejectedCert();
        if (cert != null) {
            return new Result(Kind.UNTRUSTED_CERT, null, cert);
        }
        if (e instanceof Mismatch) {
            return Result.of(Kind.NOT_S3);
        }
        if (e instanceof BackupStore.FullException) {
            return Result.of(Kind.FULL);
        }
        if (e instanceof S3Client.RefusedException) {
            switch (((S3Client.RefusedException) e).refusal) {
                case AUTH:
                    return Result.of(Kind.AUTH_FAILED);
                case NO_BUCKET:
                    return Result.of(Kind.NO_SUCH_BUCKET);
                case DENIED:
                    return Result.of(Kind.ACCESS_DENIED);
                case REGION:
                    return Result.of(Kind.WRONG_REGION);
                case CERT:
                default:
                    return Result.of(Kind.UNREACHABLE);
            }
        }
        if (e instanceof BackupStore.UnreachableException) {
            return Result.of(Kind.UNREACHABLE);
        }
        if (e instanceof S3Client.ServerException) {
            S3Client.ServerException se = (S3Client.ServerException) e;
            // HTTP, but not S3: a web page, a 405 from something else, an
            // answer with no S3 error code.
            if (se.code.isEmpty() && se.status < 500) {
                return Result.of(Kind.NOT_S3);
            }
            return Result.of(Kind.UNREACHABLE);
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("unreadable answer") || message.contains("empty answer")
                || message.contains("DOCTYPE")) {
            return Result.of(Kind.NOT_S3);
        }
        return Result.of(Kind.UNREACHABLE);
    }

    /**
     * The typed endpoint as a base URL: a scheme when missing ({@code https}),
     * no query or fragment, no trailing slash. Null when it is not an http(s)
     * address.
     */
    @Nullable
    public static HttpUrl normalizeEndpoint(@Nullable String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String e = endpoint.trim();
        if (e.isEmpty()) {
            return null;
        }
        if (!e.toLowerCase(Locale.ROOT).contains("://")) {
            e = "https://" + e;
        }
        HttpUrl url = HttpUrl.parse(e);
        if (url == null) {
            return null;
        }
        String path = url.encodedPath();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return url.newBuilder().encodedPath(path).query(null).fragment(null).build();
    }

    /** {@code https://host[:port][/base]} without a trailing slash. */
    @NonNull
    static String endpointString(@NonNull HttpUrl url) {
        String s = url.newBuilder().query(null).fragment(null).build().toString();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** "" or a key prefix ending in "/": no leading slash, empty segments
     *  dropped. */
    @NonNull
    public static String normalizePrefix(@Nullable String prefix) {
        if (prefix == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : prefix.trim().split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append(segment).append('/');
        }
        return sb.toString();
    }

    private static final Pattern[] REGION_HOSTS = {
            // Backblaze B2: s3.us-west-004.backblazeb2.com
            Pattern.compile("^s3\\.([a-z0-9-]+)\\.backblazeb2\\.com$"),
            // Wasabi: s3.eu-central-1.wasabisys.com
            Pattern.compile("^s3\\.([a-z0-9-]+)\\.wasabisys\\.com$"),
            // AWS: s3.eu-west-1.amazonaws.com, s3-eu-west-1.amazonaws.com,
            // s3.dualstack.eu-west-1.amazonaws.com
            Pattern.compile("^s3[.-](?:dualstack\\.)?([a-z]{2}-[a-z]+-\\d)\\.amazonaws\\.com$"),
            // Hetzner: fsn1.your-objectstorage.com
            Pattern.compile("^([a-z0-9-]+)\\.your-objectstorage\\.com$"),
            // Scaleway: s3.fr-par.scw.cloud
            Pattern.compile("^s3\\.([a-z0-9-]+)\\.scw\\.cloud$"),
            // DigitalOcean Spaces: ams3.digitaloceanspaces.com
            Pattern.compile("^([a-z0-9-]+)\\.digitaloceanspaces\\.com$"),
    };

    /** The region a known endpoint implies; {@code auto} for R2,
     *  {@code us-east-1} otherwise (what AWS, MinIO and Storj default to). */
    @NonNull
    public static String guessRegion(@NonNull String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".r2.cloudflarestorage.com")) {
            return "auto";
        }
        for (Pattern p : REGION_HOSTS) {
            Matcher m = p.matcher(h);
            if (m.matches()) {
                return m.group(1);
            }
        }
        return "us-east-1";
    }

    private static boolean isIpOrLocal(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.indexOf(':') >= 0 || IPV4.matcher(h).matches()
                || h.equals("localhost") || h.endsWith(".local") || h.indexOf('.') < 0;
    }
}
