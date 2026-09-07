package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The two decoders behind {@link PaymentRequests#check}: a BOLT11's encoded
 * amount and a BIP21 URI's address/amount. Both are the client's only way to
 * notice that what it is about to show the user to PAY disagrees with what the
 * quote says it costs — a swapped invoice or address.
 */
public class PaymentRequestsTest {

    private static MintClient.Quote quote(String method, String payRequest, String address, long sats) {
        return new MintClient.Quote(new byte[16], method, 1800, 600, 50, 12, new byte[8],
                payRequest, address, sats, method.equals("onchain") ? 1 : 0, false, null);
    }

    @Test
    public void bolt11AmountsDecodeAcrossMultipliersAndPrefixes() {
        assertEquals(17_000, PaymentRequests.bolt11AmountSats("lnbc170u1p4y2n4adpjgq9yysgqcqzp2"));
        assertEquals(100_000, PaymentRequests.bolt11AmountSats("lnbc1m1pabcdef"));
        assertEquals(2_500_000, PaymentRequests.bolt11AmountSats("lnbc25m1pabcdef"));
        assertEquals(1, PaymentRequests.bolt11AmountSats("lnbc10n1pabcdef"));
        assertEquals(100_000_000L, PaymentRequests.bolt11AmountSats("lnbc11pabcdef"));
        assertEquals("testnet prefix", 17_000, PaymentRequests.bolt11AmountSats("lntb170u1pabcdef"));
        assertEquals("regtest prefix", 17_000, PaymentRequests.bolt11AmountSats("lnbcrt170u1pabcdef"));
        assertEquals("case-insensitive (QR alphanumeric form)", 17_000,
                PaymentRequests.bolt11AmountSats("LNBC170U1P4Y2N4ADPJGQ9YYSGQCQZP2"));
    }

    @Test
    public void bolt11WithoutAnAmountOrBelowASatReadsMinusOne() {
        assertEquals("no amount: the payer chooses", -1, PaymentRequests.bolt11AmountSats("lnbc1pabcdef"));
        assertEquals("1.5 sat is not representable", -1, PaymentRequests.bolt11AmountSats("lnbc15n1pabcdef"));
        assertEquals(-1, PaymentRequests.bolt11AmountSats("lnbc170x1pabcdef"));
        assertEquals(-1, PaymentRequests.bolt11AmountSats("bitcoin:bc1q"));
        assertEquals(-1, PaymentRequests.bolt11AmountSats(null));
        assertEquals(-1, PaymentRequests.bolt11AmountSats(""));
    }

    @Test
    public void bip21ParsesAddressAndAmount() {
        PaymentRequests.Bip21 b = PaymentRequests.parseBip21("bitcoin:bc1qexample?amount=0.00017&label=Firedown%20credit");
        assertNotNull(b);
        assertEquals("bc1qexample", b.address);
        assertEquals(17_000, b.amountSats);
        assertEquals("scheme is case-insensitive", "bc1qexample",
                PaymentRequests.parseBip21("BITCOIN:bc1qexample").address);
        assertEquals("no amount → 0", 0, PaymentRequests.parseBip21("bitcoin:bc1qexample").amountSats);
        assertEquals("an uppercased key is not the amount", 0,
                PaymentRequests.parseBip21("bitcoin:bc1qexample?AMOUNT=0.00017").amountSats);
        assertEquals(150_000_000L, PaymentRequests.parseBip21("bitcoin:bc1qexample?amount=1.5").amountSats);
    }

    @Test
    public void bip21RejectsWhatItCannotTrust() {
        assertNull(PaymentRequests.parseBip21("lightning:lnbc1..."));
        assertNull(PaymentRequests.parseBip21("bitcoin:"));
        assertNull("sub-sat amount", PaymentRequests.parseBip21("bitcoin:bc1q?amount=0.000000001"));
        assertNull("non-numeric amount", PaymentRequests.parseBip21("bitcoin:bc1q?amount=lots"));
        assertNull(PaymentRequests.parseBip21(null));
    }

    @Test
    public void lightningInvoiceMustEncodeTheQuotedSats() throws Exception {
        PaymentRequests.check(quote("lightning", "lnbc170u1pabcdef", null, 17_000));
        try {
            PaymentRequests.check(quote("lightning", "lnbc100u1pabcdef", null, 17_000));
            fail("a 10 000-sat invoice for a 17 000-sat quote must be refused");
        } catch (MintClient.FatalException fe) {
            assertEquals(PaymentRequests.SLUG_PAY_REQUEST_MISMATCH, fe.slug);
        }
        try {
            PaymentRequests.check(quote("lightning", "lnbc1pabcdef", null, 17_000));
            fail("an amount-less invoice under a priced quote must be refused");
        } catch (MintClient.FatalException expected) {
            // the payer would pick the amount — not what was quoted
        }
    }

    @Test
    public void bip21MustNameTheQuotedAddressAndAmount() throws Exception {
        PaymentRequests.check(quote("onchain", "bitcoin:bc1qreal?amount=0.00017", "bc1qreal", 17_000));
        PaymentRequests.check(quote("onchain", "bitcoin:BC1QREAL?amount=0.00017", "bc1qreal", 17_000));
        try {
            PaymentRequests.check(quote("onchain", "bitcoin:bc1qattacker?amount=0.00017", "bc1qreal", 17_000));
            fail("a URI naming another address must be refused");
        } catch (MintClient.FatalException fe) {
            assertEquals(PaymentRequests.SLUG_PAY_REQUEST_MISMATCH, fe.slug);
        }
        try {
            PaymentRequests.check(quote("onchain", "bitcoin:bc1qreal?amount=0.0017", "bc1qreal", 17_000));
            fail("a URI carrying ten times the amount must be refused");
        } catch (MintClient.FatalException expected) {
            // the wallet would pre-fill 170 000 sats
        }
        try {
            PaymentRequests.check(quote("onchain", "bitcoin:bc1qreal", "bc1qreal", 17_000));
            fail("a URI without the amount must be refused");
        } catch (MintClient.FatalException expected) {
            // the user would have to type it; the QR is supposed to carry it
        }
    }

    @Test
    public void aMintWithoutAmountSatsIsNotChecked() throws Exception {
        PaymentRequests.check(quote("lightning", "lnbc1pabcdef", null, 0));
        PaymentRequests.check(quote("onchain", "bitcoin:bc1qother", "bc1qreal", 0));
    }
}
