package com.solarized.firedown.data.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.CreditPurchase;
import com.solarized.firedown.sync.MintClient;
import com.solarized.firedown.sync.StorageApiClient;
import com.solarized.firedown.sync.SyncSecrets;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.OkHttpClient;

/**
 * Drives the "Add storage credit" purchase: list denominations → open a quote on
 * a rail → blind → pay (Lightning invoice / Stripe hosted Checkout) → poll issue →
 * unblind → redeem at storage → new balance. The verified core lives in
 * {@link CreditPurchase} (rail-agnostic); this ViewModel wraps it in a lifecycle-
 * safe state machine so a config change or a back-out can't strand the poll loop.
 *
 * <p><b>Privacy invariant preserved:</b> the mint only ever sees the BLINDED
 * message (it can't link the credit to the payment), and storage only ever sees
 * the finished credit (never the payment). See {@link CreditPurchase}.
 *
 * <p><b>Identity.</b> A credit is redeemed into the shared recovery-code account
 * ({@link SyncIdentity}). If none exists yet (a brand-new user buying credit
 * before ever backing up), one is minted on demand via
 * {@link CloudBackupManager#createNewCode()} — the grouped code is surfaced on the
 * success screen so the user can save it (it is also always viewable from
 * Settings → Sync). The account is {@link CloudBackupManager#ensureRegistered
 * registered} once before the redeem so the signed call resolves.
 */
@HiltViewModel
public class BuyCreditViewModel extends ViewModel {

    /** How the pay step is presented, driven by the chosen rail's quote. */
    public enum Phase {
        LOADING_OPTIONS, // fetching keysets (denominations + prices)
        PICK,            // choose denomination + rail
        STARTING,        // opening the quote + blinding (brief)
        PAY_LIGHTNING,   // show BOLT11 QR, poll for payment
        PAY_STRIPE,      // opened hosted Checkout in a tab, poll for payment
        SUCCESS,         // credit redeemed — show the new balance
        ERROR
    }

    /** Rail identifiers on the wire ({@code method} in the quote request). */
    public static final String RAIL_LIGHTNING = "lightning";
    public static final String RAIL_STRIPE = "stripe";

    /** A purchasable denomination, straight from {@code /v1/mint/keys} (the server
     *  is the source of truth for denominations + prices, so nothing is hardcoded). */
    public static final class Option {
        public final int denomGbMonths;
        public final long priceCents;

        Option(int denomGbMonths, long priceCents) {
            this.denomGbMonths = denomGbMonths;
            this.priceCents = priceCents;
        }
    }

    /** Immutable UI snapshot the fragment renders. */
    public static final class UiState {
        public final Phase phase;
        public final List<Option> options;      // PICK
        public final long amountCents;          // PAY_* / SUCCESS
        public final int denomGbMonths;         // PAY_* / SUCCESS
        public final String payRequest;         // PAY_LIGHTNING (BOLT11)
        public final String checkoutUrl;        // PAY_STRIPE (hosted Checkout URL)
        public final int redeemedGbMonths;      // SUCCESS
        public final double balanceGbMonths;    // SUCCESS
        public final String mintedRecoveryCode; // SUCCESS, non-null iff a new account was created here
        public final String errorMessage;       // ERROR

        private UiState(Phase phase, List<Option> options, long amountCents, int denomGbMonths,
                        String payRequest, String checkoutUrl, int redeemedGbMonths,
                        double balanceGbMonths, String mintedRecoveryCode, String errorMessage) {
            this.phase = phase;
            this.options = options;
            this.amountCents = amountCents;
            this.denomGbMonths = denomGbMonths;
            this.payRequest = payRequest;
            this.checkoutUrl = checkoutUrl;
            this.redeemedGbMonths = redeemedGbMonths;
            this.balanceGbMonths = balanceGbMonths;
            this.mintedRecoveryCode = mintedRecoveryCode;
            this.errorMessage = errorMessage;
        }

        static UiState loading() {
            return new UiState(Phase.LOADING_OPTIONS, Collections.emptyList(), 0, 0, null, null, 0, 0, null, null);
        }

        static UiState pick(List<Option> options) {
            return new UiState(Phase.PICK, options, 0, 0, null, null, 0, 0, null, null);
        }

        static UiState starting() {
            return new UiState(Phase.STARTING, Collections.emptyList(), 0, 0, null, null, 0, 0, null, null);
        }

        static UiState pay(Phase phase, long amountCents, int denomGbMonths, String payRequest, String checkoutUrl) {
            return new UiState(phase, Collections.emptyList(), amountCents, denomGbMonths,
                    payRequest, checkoutUrl, 0, 0, null, null);
        }

        static UiState success(int redeemedGbMonths, double balanceGbMonths, int denomGbMonths,
                               long amountCents, String mintedRecoveryCode) {
            return new UiState(Phase.SUCCESS, Collections.emptyList(), amountCents, denomGbMonths,
                    null, null, redeemedGbMonths, balanceGbMonths, mintedRecoveryCode, null);
        }

        static UiState error(String message) {
            return new UiState(Phase.ERROR, Collections.emptyList(), 0, 0, null, null, 0, 0, null, message);
        }
    }

    // Poll cadence per rail. The test rail auto-settles (first poll returns);
    // Lightning waits for a manual wallet payment (~5 min), Stripe for the hosted
    // Checkout to complete (~10 min). Delay is short enough to feel responsive,
    // long enough not to hammer the mint's per-IP rate limit.
    // A gentle cadence: enough to feel responsive, slow enough to stay well under
    // the mint's per-IP limits + the Cloudflare edge rule while polling for minutes.
    private static final long POLL_DELAY_MS = 3_000L;
    private static final int POLL_MAX_LIGHTNING = 100; // ~5 min at 3s
    private static final int POLL_MAX_STRIPE = 200;    // ~10 min at 3s

    private final Context appContext;
    private final SharedPreferences prefs;
    private final OkHttpClient http;
    private final CloudBackupManager cloud;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<UiState> state = new MutableLiveData<>();

    /** Bumped whenever a flow starts or is cancelled; a poll loop bails as soon as
     *  its captured generation no longer matches (leaving the pay screen, retry). */
    private volatile int flowGen;

    /** The last-fetched denominations, so backing out of a pay screen can rebuild
     *  the picker without a re-fetch (a pay/success state doesn't carry the list). */
    private volatile List<Option> cachedOptions = Collections.emptyList();

    @Inject
    public BuyCreditViewModel(@ApplicationContext Context appContext,
                              SharedPreferences prefs,
                              OkHttpClient http,
                              CloudBackupManager cloud) {
        this.appContext = appContext;
        this.prefs = prefs;
        this.http = http;
        this.cloud = cloud;
    }

    public LiveData<UiState> getState() {
        return state;
    }

    /**
     * Fetches the purchasable denominations (active keysets) once, on demand.
     * A no-op re-fetch guard keeps a config-change re-observe from resetting an
     * in-flight/completed flow; only an ERROR state re-fetches.
     */
    public void loadOptions() {
        UiState current = state.getValue();
        if (current != null && current.phase != Phase.ERROR) {
            return; // already loaded / in-flight — don't re-fetch on re-observe
        }
        fetchOptions();
    }

    private void fetchOptions() {
        state.setValue(UiState.loading());
        final int gen = ++flowGen;
        executor.execute(() -> {
            try {
                MintClient mint = new MintClient(http, Preferences.MINT_DEFAULT_BACKEND);
                List<Option> options = new ArrayList<>();
                for (MintClient.Keyset k : mint.fetchKeys()) {
                    if (k.active) {
                        options.add(new Option(k.denomGbMonths, k.priceCents));
                    }
                }
                Collections.sort(options, Comparator.comparingInt(o -> o.denomGbMonths));
                if (options.isEmpty()) {
                    post(gen, UiState.error(appContext.getString(
                            R.string.buy_credit_error_no_options)));
                    return;
                }
                cachedOptions = options;
                post(gen, UiState.pick(options));
            } catch (Exception e) {
                post(gen, UiState.error(errorText(e)));
            }
        });
    }

    /**
     * Opens a quote for {@code denomGbMonths} on {@code method}, blinds a fresh
     * secret, transitions to the pay screen, then polls issue until the payment
     * settles — unblinding and redeeming the credit into the account's balance.
     */
    public void startPurchase(int denomGbMonths, String method) {
        state.setValue(UiState.starting());
        final int gen = ++flowGen;
        executor.execute(() -> {
            String mintedCode = null;
            byte[] code = null;
            try {
                // A credit needs an account to redeem into. Mint one on demand if
                // this device has no recovery code yet (first purchase before any
                // backup) — surfaced on success so the user can save it.
                if (!cloud.hasAccount()) {
                    mintedCode = cloud.createNewCode();
                }
                code = new SyncSecrets(appContext).load();
                if (code == null) {
                    post(gen, UiState.error(appContext.getString(
                            R.string.buy_credit_error_no_account)));
                    return;
                }
                SyncIdentity id = SyncIdentity.fromCode(code);

                MintClient mint = new MintClient(http, Preferences.MINT_DEFAULT_BACKEND);
                StorageApiClient storage = new StorageApiClient(http, cloud.backendUrl());
                // Register once (idempotent, install-marked) so the signed redeem
                // resolves against a real account.
                CloudBackupManager.ensureRegistered(prefs, storage, id);

                CreditPurchase purchase = new CreditPurchase(mint, storage);
                CreditPurchase.Session session = purchase.start(denomGbMonths, method);

                Phase payPhase = RAIL_STRIPE.equals(method) ? Phase.PAY_STRIPE : Phase.PAY_LIGHTNING;
                post(gen, UiState.pay(payPhase, session.quote.amountCents, session.quote.denomGbMonths,
                        session.quote.payRequest, session.quote.checkoutUrl));

                if (payPhase == Phase.PAY_STRIPE && session.quote.checkoutUrl == null) {
                    // The mint accepted the rail but returned no Checkout URL (card
                    // rail not configured on the server yet) — fail gracefully
                    // rather than spin on a quote that can never settle here.
                    post(gen, UiState.error(appContext.getString(
                            R.string.buy_credit_error_card_unavailable)));
                    return;
                }

                int maxPolls = RAIL_STRIPE.equals(method) ? POLL_MAX_STRIPE : POLL_MAX_LIGHTNING;
                final String finalMintedCode = mintedCode;
                for (int i = 0; i < maxPolls; i++) {
                    if (gen != flowGen) {
                        return; // user left the pay screen — stop polling
                    }
                    StorageApiClient.RedeemResult r;
                    try {
                        r = purchase.tryComplete(id, session);
                    } catch (MintClient.TransientException | StorageApiClient.TransientException te) {
                        // A 429 (rate limit) / 503 during polling is NOT terminal —
                        // the payment may still be settling. Swallow it and keep
                        // waiting; only a fatal error or the timeout ends the flow.
                        r = null;
                    }
                    if (r != null) {
                        post(gen, UiState.success(r.redeemedGbMonths, r.balanceGbMonths,
                                session.quote.denomGbMonths, session.quote.amountCents, finalMintedCode));
                        return;
                    }
                    Thread.sleep(POLL_DELAY_MS);
                }
                post(gen, UiState.error(appContext.getString(
                        R.string.buy_credit_error_timed_out)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // cancelled — no state change
            } catch (Exception e) {
                post(gen, UiState.error(errorText(e)));
            } finally {
                SyncSecrets.wipe(code);
            }
        });
    }

    /** Abandons the current pay/poll and returns to the denomination picker. The
     *  in-flight loop bails on the generation bump; any already-paid credit is not
     *  redeemed here (the user chose to go back). */
    public void backToPick() {
        flowGen++; // stop any running poll loop
        if (!cachedOptions.isEmpty()) {
            state.setValue(UiState.pick(cachedOptions));
        } else {
            fetchOptions();
        }
    }

    /** Re-fetches the denominations after an error (the guard in loadOptions()
     *  proceeds because the current phase is ERROR). */
    public void retry() {
        loadOptions();
    }

    @Override
    protected void onCleared() {
        flowGen++; // signal any running loop to stop
        executor.shutdownNow();
        super.onCleared();
    }

    /** Publishes a state on the main thread, but only if this flow generation is
     *  still current — a superseded loop (back-out / retry / cleared) is silenced. */
    private void post(int gen, UiState next) {
        main.post(() -> {
            if (gen == flowGen) {
                state.setValue(next);
            }
        });
    }

    private String errorText(Exception e) {
        // A 429/503 (the mint's per-IP quote limiter, or a CF edge throttle) is
        // transient — tell the user to wait rather than showing a generic failure.
        if (e instanceof MintClient.TransientException || e instanceof StorageApiClient.TransientException) {
            return appContext.getString(R.string.buy_credit_error_busy);
        }
        if (e instanceof MintClient.FatalException) {
            String slug = ((MintClient.FatalException) e).slug;
            if (slug != null && !slug.isEmpty()) {
                return appContext.getString(R.string.buy_credit_error_slug, slug);
            }
        }
        if (e instanceof StorageApiClient.FatalException) {
            String slug = ((StorageApiClient.FatalException) e).slug;
            if (slug != null && !slug.isEmpty()) {
                return appContext.getString(R.string.buy_credit_error_slug, slug);
            }
        }
        return appContext.getString(R.string.buy_credit_error_network);
    }
}
