package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Finds and tests a WebDAV server BEFORE it is saved: the address the user
 * typed, the sign-in, and the operations a backup needs. Nothing is stored
 * here; an {@link Kind#OK} result carries the {@link WebDavConfig} to save.
 *
 * <p><b>The address.</b> What people have is rarely the WebDAV URL. A bare
 * host gets {@code https://}; {@code davs://} and {@code dav://} (what some
 * file managers show) map to https and http. The typed address is tried
 * first, then, unless it already names Nextcloud's {@code remote.php}, the two
 * Nextcloud/ownCloud endpoints under it: {@code remote.php/dav/files/<user>/}
 * (the current one, keyed by the user id) and {@code remote.php/webdav/} (the
 * legacy one, which also works for an account that signs in with an email
 * address). The first that answers PROPFIND with a collection wins, and the
 * address SAVED is the one that answered, after redirects, as long as it is
 * on the host the user typed: a redirect to another host is answered
 * {@link Kind#MOVED}, never saved. Saving it would send the password there
 * on every later request, so a hijacked name, a captive portal or a
 * compromised redirect would be one sign-in away from the credential.
 * OkHttp already sends no Authorization across the hop itself.
 *
 * <p>User info in the typed address ({@code https://alex:pw@nas/dav/}) is
 * dropped: the address is stored in plain prefs and printed, and the form
 * has its own fields for both.
 *
 * <p><b>The write check</b> mirrors {@link FolderStore#checkWritable}: in
 * {@code Firedown Backup} (made when missing) it writes a small file and
 * deletes it, the operations a backup and a removal need. A folder this
 * check made is removed again when a later step fails.
 *
 * <p>Off the main thread only: every step is a request.
 */
public final class WebDavCheck {

    public enum Kind {
        /** Signed in, found, writable: {@link Result#config} is ready to save. */
        OK,
        /** Not an address at all. */
        BAD_ADDRESS,
        /** No answer: wrong host, offline, a timeout. */
        UNREACHABLE,
        /** The server's certificate is not one the phone (or a pin) accepts;
         *  {@link Result#cert} is what to show before trusting it. */
        UNTRUSTED_CERT,
        /** The server refused the name and password. */
        AUTH_FAILED,
        /** It answers, but not as a WebDAV collection at any address tried. */
        NOT_WEBDAV,
        /** Signed in, but cannot create, write or delete there. */
        READ_ONLY,
        /** The server is out of space. */
        FULL,
        /** The address redirects to another host, which answers as a WebDAV
         *  folder; {@link Result#movedTo} is where. Nothing was saved: the
         *  user types that address to use it. */
        MOVED,
    }

    public static final class Result {
        @NonNull
        public final Kind kind;
        @Nullable
        public final WebDavConfig config;
        @Nullable
        public final ServerTrust.CertInfo cert;
        /** For {@link Kind#MOVED}: the address the redirect landed on. */
        @Nullable
        public final String movedTo;

        Result(@NonNull Kind kind, @Nullable WebDavConfig config,
               @Nullable ServerTrust.CertInfo cert) {
            this(kind, config, cert, null);
        }

        private Result(@NonNull Kind kind, @Nullable WebDavConfig config,
                       @Nullable ServerTrust.CertInfo cert, @Nullable String movedTo) {
            this.kind = kind;
            this.config = config;
            this.cert = cert;
            this.movedTo = movedTo;
        }

        static Result of(Kind kind) {
            return new Result(kind, null, null);
        }

        static Result moved(@NonNull HttpUrl to) {
            return new Result(Kind.MOVED, null, null, to.toString());
        }
    }

    private static final byte[] WRITE_CHECK_BYTES =
            "firedown write check".getBytes(StandardCharsets.US_ASCII);

    private WebDavCheck() {
    }

    /**
     * {@link #run} for a reconnect from the sign-in page: a null
     * {@code password} means the saved one, and a null {@code pinSha256} the
     * certificate already trusted for the same server. Both come from
     * {@code saved} only when it names the same scheme, host and port (and,
     * for the password, the same user), so a saved password is never sent
     * to a different server. A null password with nothing to reuse answers
     * {@link Kind#AUTH_FAILED}: the form asks for one.
     */
    @NonNull
    public static Result runReusing(@Nullable OkHttpClient base, @Nullable WebDavConfig saved,
                                    @NonNull String address, @NonNull String user,
                                    @Nullable String password, @Nullable String pinSha256) {
        HttpUrl typed = normalize(address);
        if (typed == null) {
            return Result.of(Kind.BAD_ADDRESS);
        }
        boolean same = saved != null && sameServer(typed, HttpUrl.parse(saved.url));
        String pass = password;
        if (pass == null) {
            if (!same || !saved.user.equals(user)) {
                return Result.of(Kind.AUTH_FAILED);
            }
            pass = saved.password;
        }
        String pin = pinSha256;
        if (pin == null && same) {
            pin = saved.pinSha256;
        }
        return run(base, address, user, pass, pin);
    }

    /** Same scheme, host and port: what a saved password or pin is bound to. */
    public static boolean sameServer(@Nullable HttpUrl a, @Nullable HttpUrl b) {
        return a != null && b != null
                && a.scheme().equals(b.scheme())
                && a.host().equalsIgnoreCase(b.host())
                && a.port() == b.port();
    }

    /**
     * Runs the whole check. {@code pinSha256} is a leaf certificate the user
     * chose to trust after an {@link Kind#UNTRUSTED_CERT} answer (null the
     * first time).
     */
    @NonNull
    public static Result run(@Nullable OkHttpClient base, @NonNull String address,
                             @NonNull String user, @NonNull String password,
                             @Nullable String pinSha256) {
        HttpUrl typed = normalize(address);
        if (typed == null) {
            return Result.of(Kind.BAD_ADDRESS);
        }
        WebDavClient dav = new WebDavClient(base, typed, user, password, pinSha256);
        HttpUrl found = null;
        HttpUrl moved = null;
        boolean refused = false;
        for (HttpUrl candidate : candidates(typed, user)) {
            WebDavClient.Propfind p;
            try {
                p = dav.propfind(candidate, 0);
            } catch (IOException e) {
                return failure(dav, e);
            }
            boolean downgraded = "https".equals(typed.scheme())
                    && !"https".equals(p.finalUrl.scheme());
            if (!p.finalUrl.host().equalsIgnoreCase(typed.host())) {
                // Another host: never saved (see the class doc). Its 401 is
                // not this sign-in being refused, the hop carried none.
                if (moved == null && p.code == 207 && p.self != null && p.self.collection
                        && !downgraded) {
                    moved = WebDavStore.withTrailingSlash(p.finalUrl);
                }
                continue;
            }
            if (p.code == 401) {
                // Not final yet: a site root can challenge where its WebDAV
                // endpoint would have taken the same sign-in.
                refused = true;
                continue;
            }
            if (p.code == 207 && p.self != null && p.self.collection && !downgraded) {
                found = WebDavStore.withTrailingSlash(p.finalUrl);
                break;
            }
        }
        if (found == null) {
            if (moved != null && !refused) {
                return Result.moved(moved);
            }
            return Result.of(refused ? Kind.AUTH_FAILED : Kind.NOT_WEBDAV);
        }
        Kind written;
        try {
            written = writeCheck(dav, found);
        } catch (IOException e) {
            return failure(dav, e);
        }
        if (written != Kind.OK) {
            return Result.of(written);
        }
        WebDavConfig config = new WebDavConfig(found.toString(), user, password,
                pinSha256 == null ? null : pinSha256.toLowerCase(Locale.ROOT));
        return new Result(Kind.OK, config, null);
    }

    private static Result failure(WebDavClient dav, IOException e) {
        ServerTrust.CertInfo cert = dav.rejectedCert();
        if (cert != null) {
            return new Result(Kind.UNTRUSTED_CERT, null, cert);
        }
        return Result.of(Kind.UNREACHABLE);
    }

    /** Makes {@code Firedown Backup} when missing, writes a file there and
     *  deletes it. A folder made here is removed when a later step fails. */
    private static Kind writeCheck(WebDavClient dav, HttpUrl collection) throws IOException {
        HttpUrl root = collection.newBuilder().addPathSegment(BackupLayout.ROOT_DIR)
                .addPathSegment("").build();
        boolean made = false;
        WebDavClient.Propfind p = dav.propfind(root, 0);
        if (p.code == 401) {
            return Kind.AUTH_FAILED;
        }
        if (p.code == 404) {
            int code = dav.mkcol(root);
            Kind refused = refusal(code);
            if (code != 405 && refused != Kind.OK) {
                return refused;
            }
            made = code != 405;
        } else if (p.code != 207) {
            return Kind.READ_ONLY;
        }
        boolean passed = false;
        try {
            HttpUrl probe = root.newBuilder()
                    .addPathSegment("write-check-" + BackupLayout.newObjectId().substring(0, 8) + ".tmp")
                    .build();
            Kind refused = refusal(dav.put(probe, WRITE_CHECK_BYTES));
            if (refused != Kind.OK) {
                return refused;
            }
            int code = dav.delete(probe);
            if (code != 404) {
                refused = refusal(code);
                if (refused != Kind.OK) {
                    return refused;
                }
            }
            passed = true;
            return Kind.OK;
        } finally {
            if (!passed && made) {
                try {
                    dav.delete(root);
                } catch (IOException ignored) {
                    // best-effort; an empty folder is all it leaves
                }
            }
        }
    }

    private static Kind refusal(int code) {
        if (WebDavClient.isSuccess(code)) {
            return Kind.OK;
        }
        if (code == 401) {
            return Kind.AUTH_FAILED;
        }
        if (code == 507) {
            return Kind.FULL;
        }
        return Kind.READ_ONLY;
    }

    /**
     * The typed address as a collection URL: a scheme when missing
     * ({@code https}), {@code dav(s)://} mapped to http(s), no query or
     * fragment, a trailing slash. Null when it is not an address.
     */
    @Nullable
    public static HttpUrl normalize(@Nullable String address) {
        if (address == null) {
            return null;
        }
        String a = address.trim();
        if (a.isEmpty()) {
            return null;
        }
        String lower = a.toLowerCase(Locale.ROOT);
        if (lower.startsWith("davs://") || lower.startsWith("webdavs://")) {
            a = "https://" + a.substring(a.indexOf("://") + 3);
        } else if (lower.startsWith("dav://") || lower.startsWith("webdav://")) {
            a = "http://" + a.substring(a.indexOf("://") + 3);
        } else if (!lower.contains("://")) {
            a = "https://" + a;
        }
        HttpUrl url = HttpUrl.parse(a);
        if (url == null) {
            return null;
        }
        return WebDavStore.withTrailingSlash(url.newBuilder().username("").password("").build());
    }

    /** The addresses tried, in order (see the class doc). */
    static List<HttpUrl> candidates(HttpUrl typed, String user) {
        List<HttpUrl> out = new ArrayList<>(3);
        out.add(typed);
        if (!typed.encodedPath().contains("/remote.php/")) {
            out.add(typed.newBuilder().addPathSegment("remote.php").addPathSegment("dav")
                    .addPathSegment("files").addPathSegment(user).addPathSegment("").build());
            out.add(typed.newBuilder().addPathSegment("remote.php").addPathSegment("webdav")
                    .addPathSegment("").build());
        }
        return out;
    }
}
