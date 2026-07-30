package com.solarized.firedown.nwc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A Nostr event (NIP-01), modelling only the fields NWC needs.
 *
 * <p><b>The canonical form is hand-built, not produced by a JSON library.</b>
 * The event id is {@code sha256} over
 * {@code [0,<pubkey>,<created_at>,<kind>,<tags>,<content>]} with NIP-01's exact
 * string escaping, and a JSON serializer that escapes one character differently
 * (control characters and forward slashes are where they diverge) yields a
 * different id, therefore a signature every relay rejects — with no diagnostic
 * beyond "invalid". This mirrors {@code firedown-api internal/mint/payment/nwc/
 * event.go}, which hand-builds it for the same reason, and it is the same class
 * of trap as the sync canonical: one byte of divergence, hours in the wrong
 * layer.
 *
 * <p>For NWC the content is always base64 ciphertext, so no escape ever fires
 * in practice — the escaper is correct anyway rather than correct-by-accident.
 *
 * <p>Pure Java (no Android imports) so the host crypto harness can exercise it.
 */
public final class NostrEvent {

    /** NIP-47 request (client → wallet). */
    public static final int KIND_REQUEST = 23194;
    /** NIP-47 response (wallet → client). */
    public static final int KIND_RESPONSE = 23195;

    public String id;
    public String pubkey;
    public long createdAt;
    public int kind;
    public final List<String[]> tags = new ArrayList<>();
    public String content;
    public String sig;

    /** The NIP-01 canonical byte string the id hashes over. */
    byte[] serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("[0,\"").append(pubkey).append("\",")
                .append(createdAt).append(',')
                .append(kind).append(',');
        sb.append('[');
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            String[] tag = tags.get(i);
            for (int j = 0; j < tag.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append('"');
                escape(sb, tag[j]);
                sb.append('"');
            }
            sb.append(']');
        }
        sb.append("],\"");
        escape(sb, content == null ? "" : content);
        sb.append("\"]");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * NIP-01 string escaping: the named short escapes, {@code \\u00XX} for other
     * control characters, everything else (including all non-ASCII) verbatim.
     * Note what is deliberately NOT escaped — {@code /} and non-ASCII — because
     * escaping either is exactly how a generic JSON encoder breaks the id.
     */
    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    /** Computes the id and BIP-340-signs it, filling {@link #id} and {@link #sig}. */
    public void sign(byte[] secretKey) {
        byte[] digest = Bip340.sha256().digest(serialize());
        id = Bip340.hex(digest);
        sig = Bip340.hex(Bip340.sign(secretKey, digest));
    }

    /**
     * Recomputes the id and checks the signature against the event's OWN
     * pubkey. The caller must additionally check that pubkey is the one it
     * expects — this proves the event is internally consistent, not that it came
     * from the right author.
     */
    public boolean verify() {
        if (id == null || sig == null || pubkey == null) {
            return false;
        }
        byte[] digest = Bip340.sha256().digest(serialize());
        if (!Bip340.hex(digest).equals(id)) {
            return false;
        }
        byte[] pub = Bip340.unhex(pubkey);
        byte[] sigBytes = Bip340.unhex(sig);
        return pub != null && pub.length == 32 && sigBytes != null && sigBytes.length == 64
                && Bip340.verify(pub, digest, sigBytes);
    }

    /** Whether the event carries an {@code ["e", <id>]} tag referencing id. */
    public boolean referencesEvent(String eventId) {
        for (String[] tag : tags) {
            if (tag.length >= 2 && "e".equals(tag[0]) && tag[1].equals(eventId)) {
                return true;
            }
        }
        return false;
    }

    /** The wire JSON for publishing (["EVENT", <this>]). */
    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(id)
                .append("\",\"pubkey\":\"").append(pubkey)
                .append("\",\"created_at\":").append(createdAt)
                .append(",\"kind\":").append(kind)
                .append(",\"tags\":[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            String[] tag = tags.get(i);
            for (int j = 0; j < tag.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append('"');
                escape(sb, tag[j]);
                sb.append('"');
            }
            sb.append(']');
        }
        sb.append("],\"content\":\"");
        escape(sb, content == null ? "" : content);
        sb.append("\",\"sig\":\"").append(sig).append("\"}");
        return sb.toString();
    }
}
