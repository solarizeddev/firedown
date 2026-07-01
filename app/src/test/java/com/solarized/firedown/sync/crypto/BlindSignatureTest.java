package com.solarized.firedown.sync.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Cross-language interop test for {@link BlindSignature}: the vectors are copied
 * verbatim from the shared firedown-api tests/api-vectors/blind.json (generated
 * by the Go side), so this asserts the Java blind math is byte-identical to the
 * mint + storage. If it drifts, purchased credits would fail to redeem — this
 * catches it in CI instead of on-device.
 *
 * <p>The keyset here is a THROWAWAY test key (its private exponent {@code D} is
 * embedded only so the test can play the mint's signing role in the random
 * round-trip); a real keyset's D never leaves the mint.
 */
public class BlindSignatureTest {

    // Fixed 2048-bit test keyset from blind.json (e = 65537).
    private static final String N_HEX = "dfc01daf62ec494b89bb400e64012e4df0b52d3a60fa864ee23a82966afd24ab48616835e08ffc0432760c5221587785bb971454c5e6d573367a4c4be9ea5fc6a299ae76e62b112ff87dfc26c86226647b957fafd330ba2b08e025e4649fe7fb45d01841a50130782f708fe97fad8681360fb205478e8480695d8e684ff6294121309dfaecf03f6f46c0b8a06d0309e18044842ab38486c2963bf5df6d19a33c2e0434ffe27aa877447e4a29995de6b3045c50da7aef6ca93722528ab91847954a9fda8842af6f6a3220849c1e04d8501b38f0665097b89c4e417f22fdb59a9caacf3a4d38015bb9edbad7ea54d5f5c1c728a68a65cf15afb3e253c8b1e2c019";
    private static final String D_HEX = "3c80cd262368446b8e2b59a76a885d368b2bdab68a09c46ea942ec13f38b4f3297c86b2f0271bcd27fb8a71d40521543ced58c145e4d4c93b27c008c988c9d686f88820239bc149235ae0f948723ef40c5a047de4a0bc793a27b4613cbd7e7996d27d79f4c98953c328bcc0676557c650d32d24f1629e60f792e68b731441da432fc55b397ce063c07ff6354673fd29a03f0d1f8483a23835bba0ecf043a5471ef16dc80d906460152f6dec1811c252577c3c709be5d0cb20c204659a59918f15b3e1b6654e6a703c35f12a7d1ce68c8aff2353348c5d1850a35c9f7349d7de0796da07fc3436ad12578657caf07032e2837932ada3bd41322e6f1e2988d70af";
    private static final String KEYSET_ID = "5a150ab88353c2e5";

    private static BigInteger hi(String hex) {
        return new BigInteger(hex, 16);
    }

    private static byte[] hb(String hex) {
        int n = hex.length();
        byte[] b = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            b[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void check(BlindSignature bs, String secretHex, String rHex,
                              String fdhHex, String blindedHex, String blindSigHex,
                              String signatureHex, boolean verifies) {
        byte[] secret = hb(secretHex);
        BigInteger r = hi(rHex);
        assertEquals("fdh", hi(fdhHex), bs.fdh(secret));
        assertEquals("blinded", hi(blindedHex), bs.blindWith(secret, r).blinded);
        BigInteger sig = bs.unblind(hi(blindSigHex), r);
        assertEquals("signature", hi(signatureHex), sig);
        assertEquals("verify", verifies, bs.verify(secret, sig));
    }

    @Test
    public void reproducesGoVectors() {
        BlindSignature bs = new BlindSignature(hi(N_HEX), BlindSignature.PUBLIC_E);
        assertEquals("keyset id", KEYSET_ID, hex(bs.keysetId()));

        check(bs, "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0", "1a2b3c4d5e6f708192a3b4c5d6e7f80900112233445566778899aabbccddeeff", "cd54934b084897df29e862717eb31128e1ac24cf63b731daa48041afd3c7b94e708b05e83e65f91759235ed4403dc0f19a4dfd9487606b6ef9752d1a3766ca399a59302605fce303cf77c0dd52afccbfe90e17b068df5129034c15844cadcf3fef3c01d21a357ce46b4d4e602818c2d77962b72ba7ebf4b80ec092c501d620bb7f0f35ecead048b275ee9129394479ee5bbec75a6be22956e25492b2a1a6f3d143da47b1447ba9a7c581498861f96ba7d21aeabe6802a431ef69b99c85ec08aa18642ab31391dcf9583f6ba32934f5f1632078c0fd7303d488893fe1acb98a488922853f35d0dd383654dd65ed3ebacb0d96277deafec81b63a039546c788025", "bf95b71f5f8e5fa1bba5d7a583f5e80f44e78abd423afb638feee7f67d7082454b5010e711714b38f9fdf74cad57809109772d497b5c54aff58a5868cd45be5acdfc386a76d9cf228ebfda9a2e68dfdec0dd517c7d2521a9727658bed41983d04b070600e2c6405a42df9bb6c9b0edb01c3f9ae0ba3fbd3d4d64b9eacfcec2727662555669f955e12de24b95aa3cdb0c897c1449b831544bfdcd44f34fe20ffe635224c5955a841dfbf09fee077848d433f93e3eb22729b7b8d4634821185383753793a5b2ee4e658b8da40eb7aa4754367349c4f580096c435e748db8e74c376493e6e2c3fab0903168545c6e3be6840e1690047e39e373382be92609ce70aa", "45feb15c2f339fca8b36e3aab2baa0a8fe21fe80e451fec744b462a32e0f279147919b89cf5e364b6dc653e75402b45ce358a48f7586ff796b5054958c720d8356e20033f19dde57d2009dda567da1962930a53f1dc3a74f1fd2f92c6457a7ff9ae67e01615168bb0114f68bc8323026b1959cc0e0019d4918489c867cc3c7bf2ede40c3e8f2056c3ee1df41347d54df45561ef41fa9903a96deebe19444a961a79c02409ce77b683c3742e71f83bf3a976310db784e1f5aecec29321b94e3b4855fd4c11a94f08fc71323c8d1bd6f92c9e82341652332664a0cc23b061e8bcd03cb9b09b41ea29ccf84f1d17442063cf247657dc0aadde08cc3b31761d972b6", "0ae62330cf65df13abb2c894f6af284d96ccde1a06232df070ee93a82cc2e6f92365a76744cb6c05056e2f67361c0bc2c65d06846594ff698243322f9a086017a9578994db6ea1a3333636d78947345c5f2a3eed73c841e1318f6953d23ab30d292f729eee5ef8dd4ee4f024b2d56656ca76746225aa3c97af79ad30eaef8529a9976dd4752e2c03ebbef9a564fa803bf36275fad63f43069333f7585b0752b49947c7ec1b2563be749f035fcc13934afcf4f9e593e5af6a74814be50fe236489d81370949811b2292b5251d334ad2229abf2919af719dc62939343623b32b2d39c47469c1839e98963d0b65f9385840deeb889131a22de8f7dde1b829e61357", true);
        check(bs, "5a", "03", "477c560c50ca9ffe94cf0ff0e7b2395c95418ce11a847abe9cb7191920f3d937cc61e8be0492eed1917886389906270e865a04ee7f3e7f8ee0933b289f65b1f50d36e2ec3c607f29ed65c44d54ef534aeb096aab283e48a00290a3dde86e2748ea1421b8a3270ea39c86b58c3c3d9969c961906b9aac4378cbfe276dbcbceda3cb257c9e6de5fde5d538e0130b1ce82d10b7cb670490bee0b720e21f3cab6dd28c79a095758b7920c0fdf00493297e8778f6a39565d68f52e29d614facc1e670607dc511dd01c9a5d2c358a276282d90a8a299aa5850a0ed0201be495e7ddcb1cd0c5d221d795de5dd75b320e7f3debd21e04bc64800b65c52fffaea297d637b", "3544930e32dac409aebdc9d7925f6e92dc0b928eb7c288de3cfa1b5178fd5025387066d0f39970507ed2014a750d2a50c6a4ed625bdac891c83c6193aa044845ad9617d1caf29c5958ae80f30cd4cb390bcb6e00ba4fff1c10c5501919be831a260e785489b3f2b5dfe9d76fe386830de0a341ab81e1fa29225a3c8858f970908899256c3e044094dd5d8862f3dbea6fd92600389b9a1e83435770473c941ce0a03c43714b6eea27b5d29f7b10d20cb13f6a35331250fe3b2f29ab5fb685d2d517ba07f1bdc6f6cf51d81a6cbb8305d31f9e4c0dbf5cbbf60c9260ae485825cebdda649f3ee7a07ef6a0a2ed3459c7e90db48aa98fe29574ce79888c954c7f17", "66bdad552496e9b5967867b79e3c14596ebb042eb6e38f85b7a73f710a9d49dfaf53ecdeeca34a30bc23d1b56f8bf2425ddcf3327f2ce438c5fdf11e6d5cb281bd4f72916d4b090ac3ba73db7d225715c1bed486ed97362fe8d8e50fd77d6180d6dee990fafa5f3262bb980ba955ba57fd0b0abb6207fa2a3fb55a38d3a48adfcdc9a8b545e2b5d9533ed0df430034f39991303d38c41b8b011b52d70d140bca1d8b015e4a90d7dfd0a8147c2a9ad40c578b73ed85f03973b182fc59955a22e41fc39e6559470751df1305f495d2a343ddc055cb4f5ee782971739aa2f9c4148679190d02e2d3516aa3b6e1c76c6e7a4a71f532a06a908bcb3cc15a71ddfbb5d", "6cd4990182811100601137ecab69c0e2752565cdb29f5c9c334b40ad27337a2e5291c706ef111766fa334a0285a17898087c02826c5be88ea97d69ce1d17b0c2caa30b02c67cb368e9682556172c29d369c6c6bceaed501e509303a6beb46dd4098fab463553da8e30b962a70dabc048665e3eeae3322a38e3064d8b0bde3c0afa536ce5664651c2ddffd87fe5566a47089c9177f96d8b6f32726d9228b9e502192fbcca0f03d5725c621f8c96a83e3fc94d4198004a8cb44d8c6fa16f7b78d323767da48952279405bbd8dae69d293152fdc2108aa78ab4f71d92ef0f1b494c5b759909ccba304587fcc20243def4777a17fde6ced2b4cecd3a23254540d3d2", true);
    }

    @Test
    public void randomRoundTripVerifies() {
        BigInteger n = hi(N_HEX);
        BigInteger d = hi(D_HEX);
        BlindSignature bs = new BlindSignature(n, BlindSignature.PUBLIC_E);
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 8; i++) {
            byte[] secret = new byte[32];
            rng.nextBytes(secret);
            BlindSignature.Blinded bl = bs.blind(secret, rng);
            // The mint would compute this with its private exponent; here the test
            // key's D lets us complete the loop.
            BigInteger blindSig = bl.blinded.modPow(d, n);
            BigInteger sig = bs.unblind(blindSig, bl.r);
            assertTrue("round-trip verify", bs.verify(secret, sig));
        }
    }
}
