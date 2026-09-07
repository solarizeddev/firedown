package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Cross-checks a quote's pay instructions against its declared amount, so a
 * pay request the mint (or anything between us and it) got WRONG is refused
 * before the user is shown something to pay: a BOLT11 whose encoded amount is
 * not the quoted sats, or a BIP21 URI naming a different address or amount
 * than the bare {@code address}/{@code amount_sats} fields beside it. One
 * field swapped is a forgery or a server bug either way; both swapped
 * consistently is beyond what a client can detect, and is the mint's own
 * key's job (the blind signature the credit needs can only come from it).
 *
 * <p>Pure — two small decoders and one check — so it is unit-tested against
 * hand-built forgeries.
 */
public final class PaymentRequests {

    /** The slug a mismatch is reported under (a {@link MintClient.FatalException}). */
    public static final String SLUG_PAY_REQUEST_MISMATCH = "pay-request-mismatch";

    private PaymentRequests() {
    }

    /** A decoded BIP21 URI: the address and the amount, in sats (0 = none). */
    public static final class Bip21 {
        public final String address;
        public final long amountSats;
        Bip21(String address, long amountSats) {
            this.address = address;
            this.amountSats = amountSats;
        }
    }

    /**
     * The amount a BOLT11 invoice encodes, in sats, from its human-readable
     * part ({@code ln} + currency prefix + digits + optional multiplier
     * m/u/n/p, up to the last '1' separator). -1 when the invoice carries no
     * amount (the payer chooses) or the string isn't a BOLT11 at all; sub-sat
     * precision (a {@code p}-multiplier not on a whole sat) also reads -1.
     */
    public static long bolt11AmountSats(@Nullable String invoice) {
        if (invoice == null) {
            return -1;
        }
        String s = invoice.trim().toLowerCase(Locale.ROOT);
        int sep = s.lastIndexOf('1');
        if (!s.startsWith("ln") || sep < 3) {
            return -1;
        }
        String hrp = s.substring(2, sep);
        int i = 0;
        while (i < hrp.length() && Character.isLetter(hrp.charAt(i))) {
            i++; // currency prefix: bc / tb / bcrt / tbs
        }
        int digitsStart = i;
        while (i < hrp.length() && Character.isDigit(hrp.charAt(i))) {
            i++;
        }
        if (i == digitsStart) {
            return -1; // no amount
        }
        long digits;
        try {
            digits = Long.parseLong(hrp.substring(digitsStart, i));
        } catch (NumberFormatException e) {
            return -1;
        }
        String mult = hrp.substring(i);
        // digits are BTC × multiplier; 1 BTC = 1e8 sats.
        switch (mult) {
            case "":
                return digits * 100_000_000L;
            case "m":
                return digits * 100_000L;
            case "u":
                return digits * 100L;
            case "n":
                return digits % 10 == 0 ? digits / 10 : -1;
            case "p":
                return digits % 10_000 == 0 ? digits / 10_000 : -1;
            default:
                return -1;
        }
    }

    /** Parses {@code bitcoin:<address>[?amount=<btc>&…]}; null when it isn't one. */
    @Nullable
    public static Bip21 parseBip21(@Nullable String uri) {
        if (uri == null) {
            return null;
        }
        String s = uri.trim();
        if (s.length() < 9 || !s.substring(0, 8).equalsIgnoreCase("bitcoin:")) {
            return null;
        }
        String rest = s.substring(8);
        int q = rest.indexOf('?');
        String address = q < 0 ? rest : rest.substring(0, q);
        if (address.isEmpty()) {
            return null;
        }
        long sats = 0;
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                // BIP21 keys are case-sensitive; a wallet that uppercases them
                // drops the amount, which is exactly why the QR is encoded
                // verbatim — so accept only the spec's lowercase key here.
                if ("amount".equals(pair.substring(0, eq))) {
                    try {
                        sats = new BigDecimal(pair.substring(eq + 1))
                                .movePointRight(8).longValueExact();
                    } catch (ArithmeticException | NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return new Bip21(address, sats);
    }

    /**
     * Refuses a quote whose pay request disagrees with its declared amount /
     * address. A mint from before {@code amount_sats} existed (0) is not
     * checked — there is nothing to check against.
     */
    public static void check(@NonNull MintClient.Quote quote) throws MintClient.FatalException {
        if (quote.amountSats <= 0) {
            return;
        }
        if ("lightning".equals(quote.method)) {
            long encoded = bolt11AmountSats(quote.payRequest);
            if (encoded != quote.amountSats) {
                throw new MintClient.FatalException("invoice amount " + encoded
                        + " sats != quoted " + quote.amountSats, SLUG_PAY_REQUEST_MISMATCH);
            }
        } else if ("onchain".equals(quote.method)) {
            Bip21 uri = parseBip21(quote.payRequest);
            if (uri == null || quote.address == null
                    || !uri.address.equalsIgnoreCase(quote.address)
                    || uri.amountSats != quote.amountSats) {
                throw new MintClient.FatalException("BIP21 URI disagrees with address/amount_sats",
                        SLUG_PAY_REQUEST_MISMATCH);
            }
        }
    }
}
