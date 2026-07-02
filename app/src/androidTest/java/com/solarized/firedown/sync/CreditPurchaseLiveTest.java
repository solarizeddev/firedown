package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.sync.crypto.SyncIdentity;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import okhttp3.OkHttpClient;

/**
 * OPT-IN on-device end-to-end smoke of the buy-credit CORE (no UI) against the
 * LIVE mint + storage: register → quote → blind → pay → issue → unblind → redeem.
 * It proves the HTTP/DI/crypto wiring on a real device before the UI is built.
 *
 * <p>It is skipped unless you pass {@code -e mintLiveTest 1}, so it never runs in a
 * normal test pass. Two modes via {@code -e mintRail}:
 * <ul>
 *   <li><b>test</b> (default) — the mint's test rail auto-settles, so this is fully
 *       automated. Requires FIREDOWN_MINT_ALLOW_TEST_RAIL=1 on the mint for the run
 *       (turn it back off after). No real payment.
 *   <li><b>lightning</b> — real rail, no test-rail exposure: it logs the BOLT11
 *       invoice (logcat tag {@code MINT-LIVE}); pay it from a wallet within ~5 min
 *       and it completes. Pay your OWN Alby Hub invoice from a different wallet →
 *       the sats land back in your node, so net cost is ~a routing fee.
 * </ul>
 *
 * Run (Gradle):
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.sync.CreditPurchaseLiveTest \
 *     -Pandroid.testInstrumentationRunnerArguments.mintLiveTest=1 \
 *     -Pandroid.testInstrumentationRunnerArguments.mintRail=test
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class CreditPurchaseLiveTest {

    private static final String TAG = "MINT-LIVE";
    private static final String MINT = "https://mint.firedown.app";
    private static final String STORAGE = "https://storage.firedown.app";
    private static final int DENOM = 100; // GB-months (the smallest bucket)

    @Test
    public void buyCreditEndToEnd() throws Exception {
        String opt = InstrumentationRegistry.getArguments().getString("mintLiveTest");
        Assume.assumeTrue(
                "opt-in only: pass -e mintLiveTest 1 (see the class doc for the mint test-rail note)",
                "1".equals(opt));
        String rail = InstrumentationRegistry.getArguments().getString("mintRail", "test");

        OkHttpClient http = new OkHttpClient();
        MintClient mint = new MintClient(http, MINT);
        StorageApiClient storage = new StorageApiClient(http, STORAGE);

        // Throwaway account (fresh recovery code) — registers with storage (PoW).
        SyncIdentity id = SyncIdentity.fromCode(SyncIdentity.generateRecoveryCode());
        storage.register(id);
        Log.i(TAG, "registered throwaway account " + id.accountBase32());

        CreditPurchase purchase = new CreditPurchase(mint, storage);
        CreditPurchase.Session session = purchase.start(DENOM, rail);
        Log.i(TAG, "quote " + Hexlog(session.quote.quoteId) + " method=" + session.quote.method
                + " amount_cents=" + session.quote.amountCents);

        if ("test".equals(rail)) {
            assertTrue("test-rail quote should be auto-settled — is the rail enabled?",
                    session.quote.autoSettled);
        } else if ("lightning".equals(rail)) {
            Log.i(TAG, "PAY THIS BOLT11 within ~5 min:\n" + session.quote.payRequest);
        }

        // Poll ISSUE until paid (unblinds + verifies onto the session), then REDEEM
        // — the two-step split of the old tryComplete, so a lost redeem retries
        // redeem-only (the mint refuses a re-issue). Test rail settles at once;
        // Lightning waits for the manual payment (~5 min budget).
        int maxPolls = "lightning".equals(rail) ? 300 : 12;
        boolean paid = false;
        for (int i = 0; i < maxPolls && !paid; i++) {
            paid = purchase.issueAndUnblind(session);
            if (!paid) {
                Thread.sleep(1000);
            }
        }
        assertTrue("issue never settled (test rail off, or the invoice went unpaid)", paid);
        StorageApiClient.RedeemResult r = purchase.redeem(id, session);
        Log.i(TAG, "redeemed=" + r.redeemedGbMonths + " balance=" + r.balanceGbMonths + " GB-months");
        assertEquals("redeemed the requested denomination", DENOM, r.redeemedGbMonths);
        assertTrue("balance reflects the credit", r.balanceGbMonths >= DENOM);
    }

    private static String Hexlog(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
