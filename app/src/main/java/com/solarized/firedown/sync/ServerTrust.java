package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.okhttp.UserAgentInterceptor;
import com.solarized.firedown.sync.crypto.Hex;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.CookieJar;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * The trust and client setup every backup SERVER shares (a WebDAV server, an
 * S3 endpoint): the phone's own trust store first, plus one leaf certificate
 * the user trusted by its SHA-256 after seeing it ({@link #rejected()} is what
 * to show), and a client built from the app's shared one with none of its
 * media interceptors and no cookies.
 *
 * <p><b>Trust.</b> A server whose certificate the phone does not accept (a
 * home NAS or MinIO that made its own) can be trusted by its leaf's
 * fingerprint; the same pin also covers a certificate whose name does not
 * match the address (a server reached by IP). When neither accepts, the leaf
 * is recorded so the check can show it, and the failure is a
 * {@link BackupStore.UnreachableException} ({@link #translate}): a
 * certificate that changed stops backups until the user looks at it. The
 * trust manager is EXTENDED, so the platform's hostname-aware checks (and
 * Android's network security config) still run exactly as without it.
 */
public final class ServerTrust {

    /** The certificate a server presented and the phone did not accept. */
    public static final class CertInfo {
        /** Lowercase hex SHA-256 of the DER encoding: what a pin stores. */
        public final String sha256;
        public final String subject;
        public final String issuer;
        public final long notAfter;

        CertInfo(String sha256, String subject, String issuer, long notAfter) {
            this.sha256 = sha256;
            this.subject = subject;
            this.issuer = issuer;
            this.notAfter = notAfter;
        }

        /** {@code 4F:9A:1C:…}, the form servers show their own fingerprint in. */
        public String fingerprintDisplay() {
            StringBuilder sb = new StringBuilder(sha256.length() * 3 / 2);
            for (int i = 0; i < sha256.length(); i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(sha256.substring(i, i + 2).toUpperCase(Locale.ROOT));
            }
            return sb.toString();
        }
    }

    private final PinningTrustManager trust;

    /** @param pinSha256 a leaf certificate trusted on top of the phone's, or null */
    ServerTrust(@Nullable String pinSha256) {
        this.trust = new PinningTrustManager(systemTrustManager(), pinSha256);
    }

    /**
     * A client builder from the app's client (its DNS and connection pool),
     * or a plain one, with every interceptor removed except the app's
     * User-Agent (without it OkHttp sends "okhttp/x.y"), no cookie jar (a
     * server's session cookie never outlives the request) and this trust.
     */
    @NonNull
    OkHttpClient.Builder newBuilder(@Nullable OkHttpClient base) {
        OkHttpClient.Builder b = base != null ? base.newBuilder() : new OkHttpClient.Builder();
        List<Interceptor> userAgent = new ArrayList<>();
        for (Interceptor interceptor : b.interceptors()) {
            if (interceptor instanceof UserAgentInterceptor) {
                userAgent.add(interceptor);
            }
        }
        b.interceptors().clear();
        b.interceptors().addAll(userAgent);
        b.networkInterceptors().clear();
        HostnameVerifier defaultVerifier = (base != null ? base : new OkHttpClient()).hostnameVerifier();
        SSLContext tls;
        try {
            tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{trust}, null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no TLS", e);
        }
        return b.cookieJar(CookieJar.NO_COOKIES)
                .sslSocketFactory(tls.getSocketFactory(), trust)
                .hostnameVerifier((hostname, session) ->
                        defaultVerifier.verify(hostname, session) || trust.acceptsName(session));
    }

    /** The certificate the last refused TLS handshake presented, or null. */
    @Nullable
    CertInfo rejected() {
        return trust.rejected;
    }

    /** A refused certificate (or a name a pin does not cover) is the
     *  server's identity, not the network: it needs the user. */
    IOException translate(IOException e) {
        if (e instanceof BackupStore.UnreachableException) {
            return e;
        }
        boolean tls = e instanceof SSLHandshakeException || e instanceof SSLPeerUnverifiedException;
        if (tls && trust.rejected != null) {
            return new BackupStore.UnreachableException("server certificate not trusted", e);
        }
        if (e instanceof SSLPeerUnverifiedException) {
            return new BackupStore.UnreachableException("server certificate does not match", e);
        }
        return e;
    }

    private static X509TrustManager systemTrustManager() {
        try {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no system trust manager", e);
        }
        throw new IllegalStateException("no system trust manager");
    }

    /** The lowercase hex SHA-256 of a certificate's DER encoding. */
    static String sha256(Certificate cert) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    private static CertInfo infoOf(X509Certificate cert) {
        return new CertInfo(sha256(cert), cert.getSubjectX500Principal().getName(),
                cert.getIssuerX500Principal().getName(), cert.getNotAfter().getTime());
    }

    /** The phone's trust manager, plus one leaf the user trusted by its
     *  fingerprint. */
    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager system;
        @Nullable
        private final String pin;
        volatile CertInfo rejected;

        PinningTrustManager(X509TrustManager system, @Nullable String pin) {
            this.system = system;
            this.pin = pin == null ? null : pin.toLowerCase(Locale.ROOT);
        }

        private boolean pinned(X509Certificate[] chain) {
            return pin != null && chain != null && chain.length > 0 && pin.equals(sha256(chain[0]));
        }

        private void refused(X509Certificate[] chain, CertificateException e)
                throws CertificateException {
            if (pinned(chain)) {
                return;
            }
            if (chain != null && chain.length > 0) {
                rejected = infoOf(chain[0]);
            }
            throw e;
        }

        /** The name check the pin overrides: the leaf is the pinned one. */
        boolean acceptsName(SSLSession session) {
            Certificate[] peer;
            try {
                peer = session.getPeerCertificates();
            } catch (SSLPeerUnverifiedException e) {
                return false;
            }
            if (peer.length == 0 || !(peer[0] instanceof X509Certificate)) {
                return false;
            }
            X509Certificate leaf = (X509Certificate) peer[0];
            if (pin != null && pin.equals(sha256(leaf))) {
                return true;
            }
            rejected = infoOf(leaf);
            return false;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                refused(chain, e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            try {
                if (system instanceof X509ExtendedTrustManager) {
                    ((X509ExtendedTrustManager) system).checkServerTrusted(chain, authType, socket);
                } else {
                    system.checkServerTrusted(chain, authType);
                }
            } catch (CertificateException e) {
                refused(chain, e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            try {
                if (system instanceof X509ExtendedTrustManager) {
                    ((X509ExtendedTrustManager) system).checkServerTrusted(chain, authType, engine);
                } else {
                    system.checkServerTrusted(chain, authType);
                }
            } catch (CertificateException e) {
                refused(chain, e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }
    }
}
