package com.solarized.firedown.nwc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/**
 * Conformance tests for the Nostr Wallet Connect crypto — BIP-340 Schnorr,
 * NIP-04 encryption, the NIP-01 canonical event form, and connection-string
 * parsing.
 *
 * <p>This exists because <b>every failure mode in this stack is silent</b>. A
 * wrong signature, a hashed-instead-of-raw ECDH secret, or one character escaped
 * differently in the canonical form all produce the same symptom: the wallet
 * ignores the request and the user sees a timeout. There is no error message
 * pointing at the layer that is wrong. So the primitives are pinned against the
 * published vectors instead of against a hope.
 *
 * <p>The BIP-340 rows below are the official {@code bip-0340/test-vectors.csv},
 * embedded rather than fetched so the test needs no network. The
 * variable-length-message vectors (indices 15-18) are deliberately absent:
 * Nostr only ever signs a 32-byte event id, and {@link Bip340} enforces that.
 *
 * <p>Everything under test is pure Java, so this is an ordinary JVM unit test
 * (BouncyCastle is already an app dependency and therefore on the test
 * classpath) — no instrumentation, no device.
 */
public class NwcCryptoTest {

    /** index, secret key, public key, aux_rand, message, signature, expected. */
    private static final String[][] BIP340_VECTORS = {
        {"0", "0000000000000000000000000000000000000000000000000000000000000003",
            "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215"
                + "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            "TRUE"},
        {"1", "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE3341"
                + "8906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
            "TRUE"},
        {"2", "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1B"
                + "AB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
            "TRUE"},
        {"3", "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710",
            "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC"
                + "97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3",
            "TRUE"},  // test fails if msg is reduced modulo p or n
        {"4", "",
            "D69C3509BB99E412E68B0FE8544E72837DFA30746D8BE2AA65975F29D22DC7B9",
            "",
            "4DF3C3F68FCC83B27E9D42C90431A72499F17875C81A599B566C9889B9696703",
            "00000000000000000000003B78CE563F89A0ED9414F5AA28AD0D96D6795F9C63"
                + "76AFB1548AF603B3EB45C9F8207DEE1060CB71C04E80F593060B07D28308D7F4",
            "TRUE"},
        {"5", "",
            "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769"
                + "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            "FALSE"},  // public key not on the curve
        {"6", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A1460297556"
                + "3CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2",
            "FALSE"},  // has_even_y(R) is false
        {"7", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "1FA62E331EDBC21C394792D2AB1100A7B432B013DF3F6FF4F99FCB33E0E1515F"
                + "28890B3EDB6E7189B630448B515CE4F8622A954CFE545735AAEA5134FCCDB2BD",
            "FALSE"},  // negated message
        {"8", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769"
                + "961764B3AA9B2FFCB6EF947B6887A226E8D7C93E00C5ED0C1834FF0D0C2E6DA6",
            "FALSE"},  // negated s value
        {"9", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "0000000000000000000000000000000000000000000000000000000000000000"
                + "123DDA8328AF9C23A94C1FEECFD123BA4FB73476F0D594DCB65C6425BD186051",
            "FALSE"},  // sG - eP is infinite. Test fails in single verification if has_even_y(inf) is defined as true and x(inf) as 0
        {"10", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "0000000000000000000000000000000000000000000000000000000000000001"
                + "7615FBAF5AE28864013C099742DEADB4DBA87F11AC6754F93780D5A1837CF197",
            "FALSE"},  // sG - eP is infinite. Test fails in single verification if has_even_y(inf) is defined as true and x(inf) as 1
        {"11", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "4A298DACAE57395A15D0795DDBFD1DCB564DA82B0F269BC70A74F8220429BA1D"
                + "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            "FALSE"},  // sig[0:32] is not an X coordinate on the curve
        {"12", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F"
                + "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            "FALSE"},  // sig[0:32] is equal to field size
        {"13", "",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769"
                + "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
            "FALSE"},  // sig[32:64] is equal to curve order
        {"14", "",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC30",
            "",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769"
                + "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
            "FALSE"},  // public key is not a valid X coordinate because it exceeds the field size
    };

    @Test
    public void bip340OfficialVectors() {
        for (String[] v : BIP340_VECTORS) {
            String index = v[0];
            byte[] pub = Bip340.unhex(v[2]);
            byte[] msg = Bip340.unhex(v[4]);
            byte[] sig = Bip340.unhex(v[5]);
            boolean expected = "TRUE".equals(v[6]);

            if (!v[1].isEmpty()) {
                byte[] sec = Bip340.unhex(v[1]);
                assertEquals("vector " + index + " pubkey",
                        v[2].toLowerCase(), Bip340.hex(Bip340.xOnlyPubKey(sec)));
                // Signing is deterministic given aux_rand, so the vector pins
                // the exact bytes — not merely that the result verifies.
                assertEquals("vector " + index + " signature",
                        v[5].toLowerCase(), Bip340.hex(Bip340.sign(sec, msg, Bip340.unhex(v[3]))));
            }
            assertEquals("vector " + index + " verify",
                    expected, Bip340.verify(pub, msg, sig));
        }
    }

    @Test
    public void verifyRejectsMalformedInputWithoutThrowing() {
        byte[] pub = Bip340.unhex(BIP340_VECTORS[1][2]);
        byte[] msg = Bip340.unhex(BIP340_VECTORS[1][4]);
        byte[] sig = Bip340.unhex(BIP340_VECTORS[1][5]);
        // A hostile relay controls all of these, so each must fail closed
        // rather than throw out of the response-handling path.
        assertFalse(Bip340.verify(null, msg, sig));
        assertFalse(Bip340.verify(pub, null, sig));
        assertFalse(Bip340.verify(pub, msg, null));
        assertFalse(Bip340.verify(new byte[31], msg, sig));
        assertFalse(Bip340.verify(pub, new byte[31], sig));
        assertFalse(Bip340.verify(pub, msg, new byte[63]));
        assertFalse(Bip340.verify(new byte[32], msg, sig)); // x=0 is not on the curve
    }

    @Test
    public void signatureIsFreshEachCallButAlwaysVerifies() {
        byte[] sec = Bip340.unhex(BIP340_VECTORS[1][1]);
        byte[] pub = Bip340.xOnlyPubKey(sec);
        byte[] msg = Bip340.unhex(BIP340_VECTORS[1][4]);
        byte[] first = Bip340.sign(sec, msg);
        byte[] second = Bip340.sign(sec, msg);
        assertFalse("aux randomness should vary the signature",
                Arrays.equals(first, second));
        assertTrue(Bip340.verify(pub, msg, first));
        assertTrue(Bip340.verify(pub, msg, second));
    }

    // ---- NIP-04 ----

    private static final byte[] SEC_A =
            Bip340.unhex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef");
    private static final byte[] SEC_B =
            Bip340.unhex("c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9");

    @Test
    public void nip04RoundTripsInBothDirections() {
        byte[] pubA = Bip340.xOnlyPubKey(SEC_A);
        byte[] pubB = Bip340.xOnlyPubKey(SEC_B);
        String plain = "{\"method\":\"pay_invoice\",\"params\":{\"invoice\":\"lnbc1\"}}";

        String wire = Nip04.encrypt(SEC_A, pubB, plain);
        assertTrue("NIP-04 wire form is <b64ct>?iv=<b64iv>", wire.contains("?iv="));
        // The whole point of ECDH: the PEER derives the same key from the other
        // half of the pair. If sharedSecret ever hashed the point (which most
        // libraries do by default) this is the assertion that would catch it.
        assertEquals(plain, Nip04.decrypt(SEC_B, pubA, wire));
        assertEquals(plain, Nip04.decrypt(SEC_A, pubB, wire));
    }

    @Test
    public void nip04PreservesNonAsciiAndControlCharacters() {
        byte[] pubA = Bip340.xOnlyPubKey(SEC_A);
        byte[] pubB = Bip340.xOnlyPubKey(SEC_B);
        String plain = "café — \"quoted\"\n\t\\ 🔥";
        assertEquals(plain, Nip04.decrypt(SEC_B, pubA, Nip04.encrypt(SEC_A, pubB, plain)));
    }

    @Test
    public void nip04RejectsMalformedPayloads() {
        byte[] pubB = Bip340.xOnlyPubKey(SEC_B);
        for (String bad : new String[]{null, "", "no-iv-marker", "!!!?iv=!!!", "AAAA?iv=AAAA"}) {
            try {
                Nip04.decrypt(SEC_A, pubB, bad);
                fail("should have rejected: " + bad);
            } catch (Nip04.GeneralSecurityExceptionWrapper expected) {
                // correct
            }
        }
    }

    // ---- NIP-01 events ----

    @Test
    public void eventSignsAndVerifies() {
        NostrEvent event = requestEvent();
        assertTrue(event.verify());
        assertEquals(64, event.id.length());
        assertEquals(128, event.sig.length());
    }

    @Test
    public void tamperedEventFailsVerification() {
        NostrEvent event = requestEvent();
        String goodContent = event.content;

        event.content = goodContent + "x";
        assertFalse("content tamper must break the id", event.verify());
        event.content = goodContent;
        assertTrue(event.verify());

        event.createdAt += 1;
        assertFalse("timestamp tamper must break the id", event.verify());
        event.createdAt -= 1;

        event.tags.add(new String[]{"extra", "tag"});
        assertFalse("tag tamper must break the id", event.verify());
    }

    @Test
    public void canonicalFormSurvivesEveryEscapeClass() {
        // If the escaper diverges from NIP-01 on any of these, the id computed
        // at sign time and at verify time still agree with each other — so this
        // asserts self-consistency only. The real cross-implementation check is
        // that the wallet answers at all, which is why the escaper mirrors the
        // Go client character for character.
        NostrEvent event = new NostrEvent();
        event.pubkey = Bip340.hex(Bip340.xOnlyPubKey(SEC_A));
        event.createdAt = 1;
        event.kind = 1;
        event.tags.add(new String[]{"e", "quote\" back\\ nl\n cr\r tab\t bs\b ff\f null "});
        event.content = "forward/slash café ";
        event.sign(SEC_A);
        assertTrue(event.verify());
    }

    @Test
    public void referencesEventMatchesOnlyTheETag() {
        NostrEvent event = new NostrEvent();
        event.tags.add(new String[]{"p", "aa"});
        event.tags.add(new String[]{"e", "bb"});
        event.tags.add(new String[]{"e"}); // malformed, must not crash
        assertTrue(event.referencesEvent("bb"));
        assertFalse(event.referencesEvent("aa"));
        assertFalse(event.referencesEvent("cc"));
    }

    private static NostrEvent requestEvent() {
        NostrEvent event = new NostrEvent();
        event.pubkey = Bip340.hex(Bip340.xOnlyPubKey(SEC_A));
        event.createdAt = 1700000000L;
        event.kind = NostrEvent.KIND_REQUEST;
        event.tags.add(new String[]{"p", Bip340.hex(Bip340.xOnlyPubKey(SEC_B))});
        event.content = Nip04.encrypt(SEC_A, Bip340.xOnlyPubKey(SEC_B), "{\"method\":\"get_info\"}");
        event.sign(SEC_A);
        return event;
    }

    // ---- connection strings ----

    @Test
    public void parsesAnAlbyStyleConnectionString() throws Exception {
        String walletHex = Bip340.hex(Bip340.xOnlyPubKey(SEC_B));
        String secretHex = Bip340.hex(SEC_A);
        NwcUri uri = NwcUri.parse("nostr+walletconnect://" + walletHex
                + "?relay=wss%3A%2F%2Frelay.getalby.com%2Fv1"
                + "&secret=" + secretHex
                + "&lud16=me%40getalby.com");

        assertEquals("wss://relay.getalby.com/v1", uri.relay);
        assertEquals(walletHex, uri.walletPubkeyHex);
        assertArrayEquals(SEC_A, uri.secret);
        assertEquals(Bip340.hex(Bip340.xOnlyPubKey(SEC_A)), uri.clientPubkeyHex);
        assertEquals("me@getalby.com", uri.lud16);
        // The label is shown in the UI and may be logged; the secret must not
        // be derivable from it.
        assertFalse(uri.displayLabel().contains(secretHex));
    }

    @Test
    public void parsesTheSchemeWithoutDoubleSlash() throws Exception {
        String walletHex = Bip340.hex(Bip340.xOnlyPubKey(SEC_B));
        NwcUri uri = NwcUri.parse("nostr+walletconnect:" + walletHex
                + "?relay=wss://r.example&secret=" + Bip340.hex(SEC_A));
        assertEquals("wss://r.example", uri.relay);
    }

    @Test
    public void rejectsMalformedConnectionStrings() {
        String walletHex = Bip340.hex(Bip340.xOnlyPubKey(SEC_B));
        String secretHex = Bip340.hex(SEC_A);
        String[] bad = {
            null,
            "",
            "https://example.com",
            "lnbc10u1p...",
            "nostr+walletconnect://zz?relay=wss://r&secret=" + secretHex,
            "nostr+walletconnect://" + walletHex + "?secret=" + secretHex,      // no relay
            "nostr+walletconnect://" + walletHex + "?relay=https://r&secret=" + secretHex,
            "nostr+walletconnect://" + walletHex + "?relay=wss://r&secret=abcd", // short secret
            // 32 zero bytes: valid hex, but not a point on the curve.
            "nostr+walletconnect://" + "00".repeat(32) + "?relay=wss://r&secret=" + secretHex,
        };
        for (String s : bad) {
            try {
                NwcUri.parse(s);
                fail("should have rejected: " + s);
            } catch (NwcUri.MalformedException expected) {
                // correct
            }
        }
    }

    @Test
    public void looksLikeNwcUriIsSchemeOnlyAndCaseInsensitive() {
        assertTrue(NwcUri.looksLikeNwcUri("NOSTR+WALLETCONNECT://whatever"));
        assertTrue(NwcUri.looksLikeNwcUri("  nostrwalletconnect:abc  "));
        assertFalse(NwcUri.looksLikeNwcUri("lnbc10u1p..."));
        assertFalse(NwcUri.looksLikeNwcUri(null));
    }
}
