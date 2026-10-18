package com.solarized.firedown.sync;

import androidx.annotation.NonNull;

import com.solarized.firedown.sync.crypto.Hex;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3, by hand: the canonical request, the string
 * to sign, the signing key chain and the {@code Authorization} header, as
 * AWS's "Signature Calculations for the Authorization Header" describes them
 * for S3. Pure JDK; {@link S3Client} feeds it what it is about to send.
 *
 * <p>The S3 flavour differs from the generic SigV4 in two ways that matter:
 * the path is URI-encoded ONCE (not twice) and is never normalised (no
 * {@code ./..} collapsing, no merged slashes), and the payload hash always
 * travels as {@code x-amz-content-sha256}. Every request here signs the real
 * SHA-256 of its body (the empty hash for a body-less one): uploads are
 * buffered a part at a time precisely so they can be, which every S3
 * implementation must accept and which lets the server refuse a corrupted
 * body. Verified against AWS's published examples and botocore by
 * {@code scripts/s3-harness}.
 *
 * <p>Nothing here is ever logged: the signing key, the signature and the
 * credential are as good as the secret for the minutes the request is valid.
 */
final class S3Signer {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final String SERVICE = "s3";
    /** SHA-256 of the empty string: the payload hash of GET, HEAD, DELETE. */
    static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private S3Signer() {
    }

    /** What a signature produced: the header value and the parts a test or
     *  a debugger compares (never logged). */
    static final class Signed {
        final String authorization;
        final String signature;
        final String canonicalRequest;
        final String stringToSign;

        Signed(String authorization, String signature, String canonicalRequest,
               String stringToSign) {
            this.authorization = authorization;
            this.signature = signature;
            this.canonicalRequest = canonicalRequest;
            this.stringToSign = stringToSign;
        }
    }

    /**
     * Signs one request.
     *
     * @param method      the HTTP method, upper case
     * @param encodedPath the path exactly as sent, already URI-encoded
     *                    ({@link #encodePath})
     * @param query       the query parameters, NOT encoded (name, value);
     *                    a parameter without a value has value ""
     * @param headers     every signed header, lower-case name → value as
     *                    sent; must include {@code host},
     *                    {@code x-amz-date} and {@code x-amz-content-sha256}
     * @param payloadHash the lowercase hex SHA-256 of the body
     * @param amzDate     {@code yyyyMMdd'T'HHmmss'Z'}, the x-amz-date value
     */
    @NonNull
    static Signed sign(@NonNull String method, @NonNull String encodedPath,
                       @NonNull List<String[]> query, @NonNull SortedMap<String, String> headers,
                       @NonNull String payloadHash, @NonNull String amzDate,
                       @NonNull String region, @NonNull String accessKeyId,
                       @NonNull String secret) {
        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            canonicalHeaders.append(h.getKey()).append(':')
                    .append(trimHeaderValue(h.getValue())).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(h.getKey());
        }
        String canonicalRequest = method + '\n'
                + encodedPath + '\n'
                + canonicalQuery(query) + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;
        String stringToSign = ALGORITHM + '\n'
                + amzDate + '\n'
                + scope + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = signingKey(secret, dateStamp, region);
        String signature = Hex.encode(hmac(key, stringToSign));
        String authorization = ALGORITHM + " Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Signed(authorization, signature, canonicalRequest, stringToSign);
    }

    /** The {@code x-amz-date} value of an instant. */
    @NonNull
    static String amzDate(long epochMillis) {
        return AMZ_DATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /** kSigning = HMAC(HMAC(HMAC(HMAC("AWS4" + secret, date), region), "s3"),
     *  "aws4_request"). */
    static byte[] signingKey(String secret, String dateStamp, String region) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, SERVICE);
        return hmac(kService, "aws4_request");
    }

    /**
     * The canonical query string: every name and value URI-encoded (a slash
     * too), sorted by encoded name then encoded value, joined by {@code &}.
     * This is also the query sent, so the two cannot drift apart.
     */
    @NonNull
    static String canonicalQuery(@NonNull List<String[]> query) {
        List<String> parts = new ArrayList<>(query.size());
        for (String[] p : query) {
            parts.add(encode(p[0], true) + "=" + encode(p[1], true));
        }
        // By encoded name, then (a repeated name) by encoded value, in
        // character-code order, as the spec asks.
        Collections.sort(parts, S3Signer::compareParams);
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** Compares "name=value" pairs by name, then by value, byte-wise. */
    private static int compareParams(String a, String b) {
        int ea = a.indexOf('=');
        int eb = b.indexOf('=');
        int byName = a.substring(0, ea).compareTo(b.substring(0, eb));
        if (byName != 0) {
            return byName;
        }
        return a.substring(ea + 1).compareTo(b.substring(eb + 1));
    }

    /**
     * An object key (or any path) as S3 wants it in the request line and the
     * canonical request: every byte outside {@code A-Z a-z 0-9 - . _ ~} as
     * {@code %XX} (upper case), the slash kept. Once, never twice.
     */
    @NonNull
    static String encodePath(@NonNull String path) {
        return encode(path, false);
    }

    /** AWS's UriEncode over the UTF-8 bytes of {@code s}. */
    @NonNull
    static String encode(@NonNull String s, boolean encodeSlash) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (byte raw : bytes) {
            int c = raw & 0xff;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved || (c == '/' && !encodeSlash)) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** A header value as the canonical request holds it: trimmed, runs of
     *  spaces collapsed to one. */
    @NonNull
    static String trimHeaderValue(@NonNull String value) {
        String trimmed = value.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        boolean space = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!space) {
                    sb.append(' ');
                }
                space = true;
            } else {
                sb.append(c);
                space = false;
            }
        }
        return sb.toString();
    }

    /** Lowercase hex SHA-256 of {@code bytes}. */
    @NonNull
    static String sha256Hex(@NonNull byte[] bytes) {
        return sha256Hex(bytes, 0, bytes.length);
    }

    /** Lowercase hex SHA-256 of a slice of {@code bytes}. */
    @NonNull
    static String sha256Hex(@NonNull byte[] bytes, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes, offset, length);
            return Hex.encode(md.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no HmacSHA256", e);
        }
    }
}
