package com.solarized.firedown.sync.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the six-line canonical byte string the client Ed25519-signs and the
 * server verifies (cloud-sync-spec-api.md §2). This is the single most important
 * interop surface: if these bytes differ from the Go server's by one byte, every
 * signed request fails auth with no useful diagnostic. Pinned by the shared
 * canonical.json vectors.
 *
 * <pre>
 *   account_id_bytes \n METHOD \n path \n query \n ts_ascii \n sha256(body)
 * </pre>
 *
 * account_id is the raw 16 bytes (not base32); query has no leading '?'; the body
 * hash is the 32 raw SHA-256 bytes; there is NO trailing newline.
 */
public final class Canonical {

    private static final byte LF = (byte) '\n';

    private Canonical() {}

    public static byte[] build(byte[] accountId, String method, String path, String query,
                               long ts, byte[] body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] bodyHash = sha.digest(body == null ? new byte[0] : body);

            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    accountId.length + method.length() + path.length()
                            + (query == null ? 0 : query.length()) + 24 + bodyHash.length);
            out.write(accountId, 0, accountId.length);
            out.write(LF);
            writeAscii(out, method);
            out.write(LF);
            writeAscii(out, path);
            out.write(LF);
            writeAscii(out, query == null ? "" : query);
            out.write(LF);
            writeAscii(out, Long.toString(ts));
            out.write(LF);
            out.write(bodyHash, 0, bodyHash.length);
            return out.toByteArray();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }
}
