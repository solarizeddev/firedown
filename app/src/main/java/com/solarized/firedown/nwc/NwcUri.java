package com.solarized.firedown.nwc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A parsed Nostr Wallet Connect connection string — the "App Connection" secret
 * a wallet (Alby Hub, Coinos, Mutiny…) hands out:
 *
 * <pre>nostr+walletconnect://&lt;wallet-pubkey-hex&gt;?relay=wss://…&amp;secret=&lt;hex&gt;&amp;lud16=…</pre>
 *
 * <p>Parsed by hand rather than with {@code Uri}/{@code java.net.URI} for two
 * reasons: the {@code //} is optional in the wild (some encoders emit
 * {@code nostr+walletconnect:&lt;pubkey&gt;?…}), and a 64-char hex authority is not
 * a hostname, so platform parsers disagree about whether it belongs to
 * {@code getHost()} or {@code getSchemeSpecificPart()}. Doing it here keeps the
 * class pure Java too, so the host harness can test it.
 *
 * <p><b>This object holds a spending capability.</b> The {@code secret} is a key
 * that can move money within whatever budget the wallet granted the connection.
 * Never log it, never put it in a crash report, and store it only through
 * {@link NwcWallet} (Keystore-wrapped, backup-excluded).
 */
public final class NwcUri {

    /** The wallet service's x-only pubkey, 32 bytes. */
    public final byte[] walletPubkey;
    /** Lowercase hex of {@link #walletPubkey} — the p-tag and authors filter. */
    public final String walletPubkeyHex;
    /** The relay websocket URL. Only the first relay is used. */
    public final String relay;
    /** Our connection secret key, 32 bytes. */
    public final byte[] secret;
    /** Our x-only pubkey derived from {@link #secret}. */
    public final String clientPubkeyHex;
    /** Optional lightning address the wallet advertises; display only. */
    public final String lud16;

    private NwcUri(byte[] walletPubkey, String walletPubkeyHex, String relay,
                   byte[] secret, String clientPubkeyHex, String lud16) {
        this.walletPubkey = walletPubkey;
        this.walletPubkeyHex = walletPubkeyHex;
        this.relay = relay;
        this.secret = secret;
        this.clientPubkeyHex = clientPubkeyHex;
        this.lud16 = lud16;
    }

    /** Thrown for any malformed connection string, with a user-showable reason. */
    public static final class MalformedException extends Exception {
        public MalformedException(String message) {
            super(message);
        }
    }

    /**
     * A cheap shape test for "does this look like an NWC string at all", for
     * gating a paste/scan before the real parse. Checks the scheme only —
     * authenticity and even well-formedness are {@link #parse}'s job.
     */
    public static boolean looksLikeNwcUri(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("nostr+walletconnect:") || s.startsWith("nostrwalletconnect:");
    }

    public static NwcUri parse(String raw) throws MalformedException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new MalformedException("empty connection string");
        }
        String s = raw.trim();

        int colon = s.indexOf(':');
        if (colon < 0) {
            throw new MalformedException("not a connection string");
        }
        String scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("nostr+walletconnect") && !scheme.equals("nostrwalletconnect")) {
            throw new MalformedException("unexpected scheme " + scheme);
        }
        String rest = s.substring(colon + 1);
        // The "//" is optional in the wild — strip it if present.
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }

        int query = rest.indexOf('?');
        String pubkeyHex = (query < 0 ? rest : rest.substring(0, query))
                .trim().toLowerCase(Locale.ROOT);
        // Some wallets emit a trailing slash on the authority.
        if (pubkeyHex.endsWith("/")) {
            pubkeyHex = pubkeyHex.substring(0, pubkeyHex.length() - 1);
        }
        byte[] walletPubkey = Bip340.unhex(pubkeyHex);
        if (walletPubkey == null || walletPubkey.length != 32) {
            throw new MalformedException("wallet pubkey must be 32-byte hex");
        }
        // A pubkey that isn't on the curve can't be lifted for ECDH, and the
        // failure would otherwise surface much later as a decrypt error.
        if (Bip340.liftX(new java.math.BigInteger(1, walletPubkey)) == null) {
            throw new MalformedException("wallet pubkey is not a valid key");
        }

        String relay = null;
        String secretHex = null;
        String lud16 = null;
        if (query >= 0) {
            for (String pair : rest.substring(query + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = pair.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = urlDecode(pair.substring(eq + 1));
                switch (key) {
                    case "relay" -> {
                        if (relay == null) {
                            relay = value; // first relay wins
                        }
                    }
                    case "secret" -> secretHex = value.trim().toLowerCase(Locale.ROOT);
                    case "lud16" -> lud16 = value;
                    default -> {
                        // Unknown params (e.g. wallet-specific hints) are ignored
                        // rather than rejected, so a newer wallet still connects.
                    }
                }
            }
        }

        if (relay == null || relay.isEmpty()) {
            throw new MalformedException("no relay in connection string");
        }
        String lower = relay.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("wss://") && !lower.startsWith("ws://")) {
            throw new MalformedException("relay must be a ws:// or wss:// URL");
        }
        byte[] secret = Bip340.unhex(secretHex == null ? "" : secretHex);
        if (secret == null || secret.length != 32) {
            throw new MalformedException("secret must be 32-byte hex");
        }

        String clientHex;
        try {
            clientHex = Bip340.hex(Bip340.xOnlyPubKey(secret));
        } catch (IllegalArgumentException e) {
            throw new MalformedException("secret is not a valid key");
        }
        return new NwcUri(walletPubkey, pubkeyHex, relay, secret, clientHex, lud16);
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s; // a value that isn't percent-encoded is used verbatim
        }
    }

    /**
     * A short, non-secret label for the connection — the relay host plus the
     * first characters of the wallet pubkey. Safe to show and to log; the
     * secret never appears.
     */
    public String displayLabel() {
        String host = relay;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (lud16 != null && !lud16.isEmpty()) {
            return lud16 + " · " + host;
        }
        return host + " · " + walletPubkeyHex.substring(0, 8) + "…";
    }
}
