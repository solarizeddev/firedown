package com.solarized.firedown.data.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.nwc.NwcClient;
import com.solarized.firedown.nwc.NwcUri;
import com.solarized.firedown.nwc.NwcWallet;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.CreditPurchase;
import com.solarized.firedown.sync.CreditSettleWorker;
import com.solarized.firedown.sync.CreditSettlement;
import com.solarized.firedown.sync.MintClient;
import com.solarized.firedown.sync.OnchainPollPolicy;
import com.solarized.firedown.sync.PendingPurchase;
import com.solarized.firedown.sync.PurchaseError;
import com.solarized.firedown.sync.StorageApiClient;
import com.solarized.firedown.sync.SyncSecrets;
import com.solarized.firedown.sync.crypto.Hex;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.OkHttpClient;

/**
 * Drives the "Add storage credit" purchase: list denominations → open a quote on
 * a rail → blind → pay (Lightning invoice / on-chain Bitcoin address) → poll issue →
 * unblind → redeem at storage → new balance. The verified core lives in
 * {@link CreditPurchase} (rail-agnostic); this ViewModel wraps it in a lifecycle-
 * safe state machine so a config change or a back-out can't strand the poll loop.
 *
 * <p><b>Privacy invariant preserved:</b> the mint only ever sees the BLINDED
 * message (it can't link the credit to the payment), and storage only ever sees
 * the finished credit (never the payment). See {@link CreditPurchase}.
 *
 * <p><b>Identity.</b> A credit is redeemed into the shared recovery-code account
 * ({@link SyncIdentity}), which MUST already exist: the Cloud hub gates the whole
 * downloads-backup surface (this buy flow included) on a key, so the user has
 * created or recovered one — and saved it — before reaching here. This flow does
 * NOT mint a key on demand. The old code did ({@code createNewCode} at flow
 * start), which created + enabled an account the instant Continue was tapped, so
 * backing out of the (since-removed) card checkout left a ghost account with an UNSAVED key and
 * made paying-before-saving-the-key possible; requiring the key up front removes
 * both. The account is {@link CloudBackupManager#ensureRegistered registered}
 * once before the redeem so the signed call resolves.
 */
@HiltViewModel
public class BuyCreditViewModel extends ViewModel {

    /** How the pay step is presented, driven by the chosen rail's quote. */
    public enum Phase {
        LOADING_OPTIONS, // fetching keysets (denominations + prices)
        PICK,            // choose denomination + rail
        STARTING,        // opening the quote + blinding (brief)
        PAY_LIGHTNING,   // show BOLT11 QR, poll for payment
        PAY_ONCHAIN,     // show a BIP21 address QR, poll (slowly) for confirmation
        SUCCESS,         // credit redeemed — show the new balance
        ERROR
    }

    /** Rail identifiers on the wire ({@code method} in the quote request). Cards
     *  (Stripe) were a third rail and were REMOVED server-side — the processor
     *  was the one party that legally knew the buyer, and card settlement was
     *  the only kind reversible after an irrevocable credit was issued. Both
     *  remaining rails are Bitcoin: final once confirmed, no processor. */
    public static final String RAIL_LIGHTNING = "lightning";
    public static final String RAIL_ONCHAIN = "onchain";

    /** A purchasable keyset, straight from {@code /v1/mint/keys} (the server is the
     *  source of truth for denominations, prices AND the plan-grid tiles, so
     *  nothing is hardcoded — the operator sets sizes/prices via {@code --genkey}).
     *  {@code sizeGb}/{@code durationMonths} are the "Up to X GB for Y" tile, both
     *  0 for a legacy denomination-only keyset (the client then shows a flat list). */
    public static final class Option {
        public final String keysetIdHex;   // the exact keyset this tile buys
        public final int denomGbMonths;
        public final long priceCents;
        public final int sizeGb;
        public final int durationMonths;

        Option(String keysetIdHex, int denomGbMonths, long priceCents, int sizeGb, int durationMonths) {
            this.keysetIdHex = keysetIdHex;
            this.denomGbMonths = denomGbMonths;
            this.priceCents = priceCents;
            this.sizeGb = sizeGb;
            this.durationMonths = durationMonths;
        }

        /** True when this option is a plan-grid tile (size × duration). */
        public boolean isPlan() {
            return sizeGb > 0 && durationMonths > 0;
        }
    }

    /** Immutable UI snapshot the fragment renders. */
    public static final class UiState {
        public final Phase phase;
        public final List<Option> options;      // PICK
        public final List<String> methods;      // PICK — the rails the mint offers ("lightning"/"onchain")
        public final long amountCents;          // PAY_* / SUCCESS
        public final int denomGbMonths;         // PAY_* / SUCCESS
        public final int sizeGb;                // PAY_* / SUCCESS — plan tile (0 if legacy)
        public final int durationMonths;        // PAY_* / SUCCESS — plan tile (0 if legacy)
        public final String payRequest;         // PAY_LIGHTNING (BOLT11) / PAY_ONCHAIN (BIP21 URI)
        public final String address;            // PAY_ONCHAIN (bare receive address)
        public final long amountSats;           // PAY_ONCHAIN (exact sats to send)
        public final int minConfirmations;      // PAY_ONCHAIN (confirmations before the mint issues)
        public final boolean paymentDetected;   // PAY_ONCHAIN: the mint saw the payment, confirming
        public final int redeemedGbMonths;      // SUCCESS
        public final double balanceGbMonths;    // SUCCESS
        public final String errorMessage;       // ERROR
        public final boolean offerLightning;    // ERROR: the on-chain rail refused; Lightning is available

        // NOTE: there is no "minted recovery code" on SUCCESS anymore. The buy flow
        // no longer creates an account (the Cloud hub gates it on an existing key),
        // so the code is always created + saved BEFORE the user ever reaches here.

        private UiState(Phase phase, List<Option> options, List<String> methods, long amountCents,
                        int denomGbMonths, int sizeGb, int durationMonths, String payRequest,
                        String address, long amountSats, int minConfirmations, boolean paymentDetected,
                        int redeemedGbMonths, double balanceGbMonths, String errorMessage,
                        boolean offerLightning) {
            this.phase = phase;
            this.options = options;
            this.methods = methods;
            this.amountCents = amountCents;
            this.denomGbMonths = denomGbMonths;
            this.sizeGb = sizeGb;
            this.durationMonths = durationMonths;
            this.payRequest = payRequest;
            this.address = address;
            this.amountSats = amountSats;
            this.minConfirmations = minConfirmations;
            this.paymentDetected = paymentDetected;
            this.redeemedGbMonths = redeemedGbMonths;
            this.balanceGbMonths = balanceGbMonths;
            this.errorMessage = errorMessage;
            this.offerLightning = offerLightning;
        }

        static UiState loading() {
            return new UiState(Phase.LOADING_OPTIONS, Collections.emptyList(), Collections.emptyList(),
                    0, 0, 0, 0, null, null, 0, 0, false, 0, 0, null, false);
        }

        static UiState pick(List<Option> options, List<String> methods) {
            return new UiState(Phase.PICK, options, methods, 0, 0, 0, 0, null, null, 0, 0, false, 0, 0, null, false);
        }

        static UiState starting() {
            return new UiState(Phase.STARTING, Collections.emptyList(), Collections.emptyList(),
                    0, 0, 0, 0, null, null, 0, 0, false, 0, 0, null, false);
        }

        static UiState pay(Phase phase, long amountCents, int denomGbMonths, int sizeGb, int durationMonths,
                           String payRequest, String address, long amountSats, int minConfirmations,
                           boolean paymentDetected) {
            return new UiState(phase, Collections.emptyList(), Collections.emptyList(), amountCents,
                    denomGbMonths, sizeGb, durationMonths, payRequest, address, amountSats,
                    minConfirmations, paymentDetected, 0, 0, null, false);
        }

        static UiState success(int redeemedGbMonths, double balanceGbMonths, int denomGbMonths,
                               int sizeGb, int durationMonths, long amountCents) {
            return new UiState(Phase.SUCCESS, Collections.emptyList(), Collections.emptyList(), amountCents,
                    denomGbMonths, sizeGb, durationMonths, null, null, 0, 0, false,
                    redeemedGbMonths, balanceGbMonths, null, false);
        }

        static UiState error(String message) {
            return error(message, false);
        }

        static UiState error(String message, boolean offerLightning) {
            return new UiState(Phase.ERROR, Collections.emptyList(), Collections.emptyList(),
                    0, 0, 0, 0, null, null, 0, 0, false, 0, 0, message, offerLightning);
        }
    }

    // Poll cadence per rail. The test rail auto-settles (first poll returns);
    // Lightning waits for a manual wallet payment (~5 min at 3 s — enough to
    // feel responsive, slow enough to stay well under the mint's per-IP limits
    // + the Cloudflare edge rule). On-chain is OnchainPollPolicy: 3 s until
    // the mint has SEEN the payment, 30 s after (a confirmation is a block),
    // bounded by the LATEST expires_at the mint reported rather than a count.
    // Either wait ending KEEPS the record (the payment may still settle) and
    // the settle worker / the next wizard entry resumes it.
    private static final long POLL_DELAY_MS = 3_000L;
    private static final int POLL_MAX_LIGHTNING = 100;      // ~5 min at 3s

    private final Context appContext;
    private final SharedPreferences prefs;
    private final OkHttpClient http;
    private final CloudBackupManager cloud;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** The running purchase/resume flow, so {@link #cancelPendingPurchase} can
     *  INTERRUPT its poll sleep. The executor is single-threaded and the clear
     *  is queued behind the flow, which keeps the ordering (every save of the
     *  cancelled flow lands before the clear) — but an on-chain poll sleeps up
     *  to 30 s between asks, and without the interrupt the clear waited out
     *  that whole sleep while the picker was already on screen: a process
     *  death in that window resurrected the cancelled quote on the next entry
     *  (its record still on disk, still marked submitted), and the settle
     *  worker had already been cancelled for it. The gen bump still ends the
     *  loop; the interrupt only wakes it up to notice. */
    private volatile Future<?> flowFuture;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<UiState> state = new MutableLiveData<>();

    /** Bumped whenever a flow starts or is cancelled; a poll loop bails as soon as
     *  its captured generation no longer matches (leaving the pay screen, retry). */
    private volatile int flowGen;

    /** True once THIS flow's payment was submitted (connected wallet said paid,
     *  or an on-chain address was shown). Guards {@link #onCleared}'s
     *  abandon-cleanup: a submitted record is money plausibly in flight, so it
     *  must survive leaving the wizard even though issue hasn't confirmed it —
     *  see {@link #markPaymentSubmitted}. Volatile: set on main, read on the
     *  executor. Reset per flow in {@link #startPurchase}. */
    private volatile boolean paymentSubmitted;

    /**
     * Outcome of a Nostr Wallet Connect auto-payment attempt, surfaced SEPARATELY
     * from {@link UiState} on purpose: paying from a connected wallet does not
     * move the purchase state machine at all. The mint's settlement poll is what
     * completes a purchase, exactly as it does when the user pays the QR from
     * another app — so this stream reports only what the wallet said, and the
     * existing poll still owns the transition to SUCCESS.
     */
    public enum WalletPay {
        /** No attempt in flight. */
        IDLE,
        /** The request is with the wallet. */
        PAYING,
        /** The wallet reported the invoice paid. The poll takes it from here. */
        SENT,
        /** The wallet refused, or we never heard back — see {@link #walletPayError}. */
        FAILED
    }

    private final MutableLiveData<WalletPay> walletPay = new MutableLiveData<>(WalletPay.IDLE);
    /** Human-readable reason for the last {@link WalletPay#FAILED}; null otherwise. */
    private volatile String walletPayError;

    /** The last-fetched denominations, so backing out of a pay screen can rebuild
     *  the picker without a re-fetch (a pay/success state doesn't carry the list). */
    private volatile List<Option> cachedOptions = Collections.emptyList();
    /** The rails the mint advertised on /keys ({@code methods}) — the picker
     *  offers exactly these. */
    private volatile List<String> cachedMethods = Collections.emptyList();
    /** The tile the current/last purchase was started for, so a refused rail
     *  can offer a one-tap retry on Lightning for the same tile. */
    private volatile Option lastOption;

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
        // A credit paid-but-not-yet-redeemed from a previous run (process death /
        // left the wizard) is resumed here before showing the picker — so the money
        // isn't lost. Only on a FRESH entry (guard above), never on re-observe.
        if (resumePendingIfAny()) {
            return;
        }
        fetchOptions();
    }

    private void fetchOptions() {
        state.setValue(UiState.loading());
        final int gen = ++flowGen;
        executor.execute(() -> {
            try {
                MintClient mint = new MintClient(http, Preferences.MINT_DEFAULT_BACKEND);
                MintClient.Catalog catalog = mint.fetchCatalog();
                List<Option> all = new ArrayList<>();
                boolean anyPlan = false;
                for (MintClient.Keyset k : catalog.keysets) {
                    if (k.active) {
                        all.add(new Option(Hex.encode(k.id), k.denomGbMonths, k.priceCents,
                                k.sizeGb, k.durationMonths));
                        anyPlan |= k.isPlan();
                    }
                }
                // If ANY plan-grid tile exists, show ONLY the plan tiles (the grid),
                // ignoring any legacy denom-only keysets still active — a clean
                // migration. With no plan tiles, fall back to the flat denom list.
                List<Option> options = new ArrayList<>();
                for (Option o : all) {
                    if (!anyPlan || o.isPlan()) {
                        options.add(o);
                    }
                }
                if (anyPlan) {
                    // Grid order: by duration (the toggle), then size (the tiles).
                    Collections.sort(options, Comparator
                            .comparingInt((Option o) -> o.durationMonths)
                            .thenComparingInt(o -> o.sizeGb));
                } else {
                    Collections.sort(options, Comparator.comparingInt(o -> o.denomGbMonths));
                }
                if (options.isEmpty()) {
                    post(gen, UiState.error(appContext.getString(
                            R.string.buy_credit_error_no_options)));
                    return;
                }
                cachedOptions = options;
                cachedMethods = catalog.methods;
                post(gen, UiState.pick(options, catalog.methods));
            } catch (Exception e) {
                post(gen, UiState.error(errorText(e)));
            }
        });
    }

    /**
     * Opens a quote for the chosen {@code opt} (a plan tile / denomination) on
     * {@code method}, blinds a fresh secret, transitions to the pay screen, then
     * polls issue until the payment settles — unblinding and redeeming the credit
     * into the account's balance. Always quotes by the option's exact keyset id.
     */
    public void startPurchase(Option opt, String method) {
        // A SUBMITTED, not-yet-issued record is a payment that may land any
        // second, and the blob holds one record: starting a new purchase over
        // it would overwrite the only copy of that payment's blinding secret.
        // The picker is normally unreachable while one exists (entry resumes
        // it; Back on a pay screen is a confirmed cancel), so this is the
        // belt-and-braces door: resume the live one instead of overwriting.
        PendingPurchase live = PendingPurchase.load(appContext);
        if (live != null && live.submitted && (live.sigHex == null || live.sigHex.isEmpty())) {
            if (resumePendingIfAny()) {
                return;
            }
        }
        lastOption = opt;
        final int sizeGb = opt.sizeGb;
        final int durationMonths = opt.durationMonths;
        final String keysetIdHex = opt.keysetIdHex;
        paymentSubmitted = false; // fresh flow — set below once the pay UI exists
        state.setValue(UiState.starting());
        final int gen = ++flowGen;
        flowFuture = executor.submit(() -> {
            byte[] code = null;
            try {
                // A key must ALREADY exist — the Cloud hub gates the whole
                // downloads-backup surface (incl. this buy flow) on hasAccount(),
                // so the user has created or recovered a code before they can get
                // here. We deliberately do NOT mint one on demand: the old code
                // did (createNewCode at flow start), which created + enabled an
                // account the instant Continue was tapped — so backing out of the
                // (since-removed) card checkout left a ghost account ("11 GB
                // included") with an UNSAVED key, and paid-before-saved-key was
                // possible. Require the
                // key instead; if it's somehow absent, fail cleanly.
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
                CreditPurchase.Session session = purchase.startByKeyset(keysetIdHex, method);

                // Persist the pending purchase BEFORE showing the pay UI — the
                // blinding secret + quote now survive process death / the user
                // leaving to a wallet or 3-D-Secure app, so a paid-but-not-yet-
                // redeemed credit is recoverable (resumePendingIfAny) instead of
                // lost money.
                PendingPurchase pending = CreditPurchase.toPending(session);
                boolean onchain = RAIL_ONCHAIN.equals(method);
                if (onchain) {
                    if (session.quote.address == null || session.quote.amountSats <= 0) {
                        // The mint accepted the rail but returned nothing payable
                        // (a misconfigured node) — fail rather than show an
                        // empty address that can never settle.
                        post(gen, UiState.error(appContext.getString(
                                R.string.buy_credit_error_rail_unavailable)));
                        return;
                    }
                }
                // EVERY record counts as SUBMITTED from the moment its pay UI
                // exists — on-chain AND Lightning. The user can pay an address
                // OR an invoice from any wallet with no signal back to this
                // app (an external wallet scanning the QR, or the copied
                // invoice pasted on another device, is the common path), so
                // "unpaid because we saw no payment" is never a safe
                // assumption. History: only the on-chain record was marked,
                // and only the CONNECTED-wallet path marked a Lightning one —
                // so a Lightning invoice paid from any other wallet and then
                // left (onCleared) or backed out of (a new purchase overwrote
                // the blob) lost the blinding secret while the mint held a
                // paid, unissued quote: real money, gone. A record now leaves
                // only through the explicit cancel confirmation
                // (cancelPendingPurchase) or a dead quote.
                pending = pending.withSubmitted();
                paymentSubmitted = true;
                pending.save(appContext);
                // Settlement may land after this screen is gone (on-chain takes
                // minutes to hours; a Lightning invoice paid from another device
                // can settle after the user left) and the poll below lives only
                // while the screen is open — the periodic settle job finishes
                // the purchase when it isn't.
                CreditSettleWorker.schedule(appContext);

                Phase payPhase = onchain ? Phase.PAY_ONCHAIN : Phase.PAY_LIGHTNING;
                post(gen, UiState.pay(payPhase, session.quote.amountCents, session.quote.denomGbMonths,
                        sizeGb, durationMonths, session.quote.payRequest, session.quote.address,
                        session.quote.amountSats, session.quote.minConfirmations, false));

                completePurchase(gen, purchase, session, id, sizeGb, durationMonths,
                        method, pending);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // cancelled — no state change
            } catch (Exception e) {
                // A refused on-chain quote (the node is syncing) offers the one
                // rail that can still take the same tile right now.
                boolean offerLightning = RAIL_ONCHAIN.equals(method)
                        && PurchaseError.classify(e).kind == PurchaseError.Kind.RAIL_UNAVAILABLE
                        && cachedMethods.contains(RAIL_LIGHTNING);
                post(gen, UiState.error(errorText(e), offerLightning));
            } finally {
                SyncSecrets.wipe(code);
            }
        });
    }

    /** The error screen's "Pay with Lightning instead": the same tile, the
     *  other rail. Only offered when the mint refused the on-chain quote and
     *  lists Lightning. */
    public void switchToLightning() {
        Option opt = lastOption;
        if (opt != null) {
            startPurchase(opt, RAIL_LIGHTNING);
        }
    }

    /**
     * The poll-to-completion loop, shared by a fresh purchase and a resumed one.
     * ISSUE and REDEEM are split so their retry granularity differs — the mint
     * mints one credit per quote and refuses a re-issue, so once issue succeeds we
     * persist the signature and ONLY ever retry redeem:
     *
     * <ul>
     *   <li>issue: transient (429/503) → keep polling; not-paid-yet → keep polling;
     *       a fatal (e.g. 410 quote-expired) → clear the record + end (no charge on
     *       an unpaid expired quote).</li>
     *   <li>once paid: persist the unblinded sig, so a crash mid-redeem resumes at
     *       redeem-only.</li>
     *   <li>redeem: a bare IOException / transient → RETRY REDEEM (never re-issue),
     *       so a lost response doesn't fail a purchase whose money was taken; a
     *       {@code credit-spent} 409 means an earlier redeem already applied it →
     *       SUCCESS; any other fatal → error.</li>
     * </ul>
     *
     * On success/credit-spent the record is cleared. On timeout it is deliberately
     * KEPT — the payment may still settle, and the next entry resumes it.
     */
    private void completePurchase(int gen, CreditPurchase purchase, CreditPurchase.Session session,
                                  SyncIdentity id, int sizeGb, int durationMonths, String method,
                                  PendingPurchase pending)
            throws InterruptedException {
        boolean onchain = RAIL_ONCHAIN.equals(method);
        // On-chain: cadence + deadline from the policy, seeded with the expiry
        // the record carries (the original hour, or a later one a previous
        // run persisted from a pending reply). Lightning: the fixed count.
        OnchainPollPolicy policy = onchain ? new OnchainPollPolicy(pending.expiresAt) : null;
        int lightningPolls = 0;
        boolean pastDeadlineChecked = false;
        boolean detectedShown = false;
        while (true) {
            if (gen != flowGen) {
                return; // user left the pay screen — stop polling (record kept for resume)
            }
            if (onchain) {
                if (policy.pastDeadline(System.currentTimeMillis())) {
                    // The local clock says the latest known expiry has passed.
                    // Ask the mint ONE more time and let its answer decide — a
                    // 410 clears the record below, a further extension keeps
                    // going — then stop; the clock is a stopping rule, not the
                    // truth about the quote.
                    if (pastDeadlineChecked) {
                        break;
                    }
                    pastDeadlineChecked = true;
                }
            } else if (lightningPolls++ >= POLL_MAX_LIGHTNING) {
                break;
            }
            boolean issued;
            try {
                issued = purchase.issueAndUnblind(session);
                if (!issued && onchain) {
                    policy.observe(session.paymentPending(), session.pendingExpiresAt());
                    String extended = session.pendingExpiresAt();
                    if (extended != null && !extended.equals(pending.expiresAt)) {
                        // The mint pushed the expiry out for a payment it can
                        // see; persist it so a resume waits until THAT
                        // deadline, not the original hour.
                        pending = pending.withExpiresAt(extended);
                        pending.save(appContext);
                    }
                    if (session.paymentPending() && !detectedShown) {
                        // Flip the pay screen to "detected, confirming" once;
                        // the record is already submitted-marked for this rail.
                        detectedShown = true;
                        post(gen, UiState.pay(Phase.PAY_ONCHAIN, session.quote.amountCents,
                                session.quote.denomGbMonths, sizeGb, durationMonths,
                                session.quote.payRequest, session.quote.address,
                                session.quote.amountSats, session.quote.minConfirmations, true));
                    }
                }
            } catch (MintClient.FatalException fe) {
                // The record is the ONLY copy of the blinding secret, so it may be
                // dropped only when the credit is provably dead. An UNPAID expired
                // quote was never charged (nothing to lose); anything else — a
                // 409, a mint hiccup — keeps the record so resumePendingIfAny can
                // retry, because clearing it on a quote whose payment DID settle
                // destroys real money. FatalException is more specific than
                // IOException, so it MUST be caught first.
                //
                // ON-CHAIN is the exception to "expired = dead": the mint polls
                // its node before the expiry test and keeps the row for weeks,
                // so a late broadcast to the address still settles — if a
                // client keeps asking. Keep the record (and the settle worker)
                // for the late window and say so, instead of clearing on the
                // first 410 and stranding the payment that lands an hour later.
                if (isDeadQuote(fe.slug)) {
                    if (onchain && !OnchainPollPolicy.beyondLateWindow(
                            pending.expiresAt, System.currentTimeMillis())) {
                        post(gen, UiState.error(appContext.getString(
                                R.string.buy_credit_error_btc_expired_kept)));
                        return;
                    }
                    PendingPurchase.clear(appContext);
                    CreditSettleWorker.cancel(appContext);
                }
                post(gen, UiState.error(errorText(fe)));
                return;
            } catch (IOException io) {
                // TransientException (429/503) OR a bare network drop during issue.
                // The quote isn't dead and nothing was minted yet, so keep polling —
                // the payment may still settle and issue is safe to retry (the mint
                // is idempotent-once per quote on the same blinded message).
                issued = false;
            }
            if (!issued) {
                Thread.sleep(onchain ? policy.nextDelayMs() : POLL_DELAY_MS);
                continue;
            }

            // Paid + issued: persist the sig so a crash before/at redeem resumes
            // redeem-only (never re-issue, which the mint refuses).
            pending = pending.withSig(session.sig());
            pending.save(appContext);

            StorageApiClient.RedeemResult r;
            try {
                r = purchase.redeem(id, session);
            } catch (StorageApiClient.FatalException fe) {
                if (StorageApiClient.SLUG_CREDIT_SPENT.equals(fe.slug)) {
                    // An earlier redeem (whose response we lost) already applied it.
                    r = StorageApiClient.RedeemResult.applied(session.quote.denomGbMonths);
                } else {
                    // NEVER clear here. At this point the credit is PAID and ISSUED
                    // and this record holds the only copy of it. StorageApiClient
                    // maps every 4xx except 429 to FatalException, and some of those
                    // are transient — `unknown-keyset` in particular just means
                    // storage's mint-key cache hasn't caught up with a newly minted
                    // keyset — so clearing on them silently destroyed money the user
                    // had already paid. The record survives; resumePendingIfAny
                    // retries the redeem (which is idempotent server-side: the burn
                    // is keyed on sha256(secret), and a re-redeem returns
                    // credit-spent, handled above as success).
                    post(gen, UiState.error(errorText(fe)));
                    return;
                }
            } catch (IOException io) {
                // Transient (429/503) OR a bare socket drop with the credit maybe
                // burned server-side — RETRY REDEEM ONLY. The sig is persisted, so
                // even a crash here resumes safely; storage's burn is idempotent.
                Thread.sleep(POLL_DELAY_MS);
                continue;
            }

            // Success — book it (plan merge, enabled flag, top-up receipt
            // snapshot, clear the record) through the settler shared with the
            // background CreditSettleWorker, which may have reached this same
            // credit first: then the redeem above answered credit-spent and
            // commitRedeemed finds the record gone and books nothing twice. Done
            // even on a gen mismatch (the purchase DID settle); only the success
            // post is gen-gated.
            CreditSettlement.commitRedeemed(appContext, prefs, cloud, pending.quoteIdHex,
                    sizeGb, durationMonths);
            CreditSettleWorker.cancel(appContext);
            post(gen, UiState.success(r.redeemedGbMonths, r.balanceGbMonths,
                    session.quote.denomGbMonths, sizeGb, durationMonths,
                    session.quote.amountCents));
            return;
        }
        // Out of budget — KEEP the record (payment may still settle; the settle
        // worker and the next wizard entry both pick it up). On-chain the
        // budget is the latest expiry, so the honest message is "expired" —
        // unless the mint has SEEN money, which is real and confirming slowly,
        // not "nothing received". Lightning's count running out is the plain
        // "not received in time".
        int message;
        if (!onchain) {
            message = R.string.buy_credit_error_timed_out;
        } else if (session.paymentPending()) {
            message = R.string.buy_credit_error_btc_still_waiting;
        } else {
            message = R.string.buy_credit_error_expired;
        }
        post(gen, UiState.error(appContext.getString(message)));
    }

    /**
     * If a purchase was interrupted (process death / left the wizard) after paying
     * but before redeeming, resume it: rebuild the session from the persisted
     * record and run the completion loop. Re-shows the pay UI when still unpaid (so
     * the user can finish paying); goes straight through when the credit was
     * already issued. Called from {@link #loadOptions} on entry, before the picker.
     */
    private boolean resumePendingIfAny() {
        final PendingPurchase pending = PendingPurchase.load(appContext);
        if (pending == null) {
            return false;
        }
        state.setValue(UiState.starting());
        final int gen = ++flowGen;
        flowFuture = executor.submit(() -> {
            byte[] code = null;
            try {
                code = new SyncSecrets(appContext).load();
                if (code == null) {
                    // No account key to redeem into (shouldn't happen once a purchase
                    // started) — drop the orphaned record and fall back to the picker.
                    PendingPurchase.clear(appContext);
                    fetchOptions();
                    return;
                }
                SyncIdentity id = SyncIdentity.fromCode(code);
                MintClient mint = new MintClient(http, Preferences.MINT_DEFAULT_BACKEND);
                StorageApiClient storage = new StorageApiClient(http, cloud.backendUrl());
                CloudBackupManager.ensureRegistered(prefs, storage, id);
                CreditPurchase purchase = new CreditPurchase(mint, storage);
                CreditPurchase.Session session = purchase.restore(pending);

                // Not yet issued → re-show the pay affordance so the user can still
                // pay (the poll also settles it if they already did). Already issued
                // → stay on the brief "starting" spinner and go straight to redeem.
                if (pending.sigHex == null || pending.sigHex.isEmpty()) {
                    Phase payPhase;
                    if (RAIL_ONCHAIN.equals(pending.method)) {
                        payPhase = Phase.PAY_ONCHAIN;
                    } else if (RAIL_LIGHTNING.equals(pending.method)) {
                        payPhase = Phase.PAY_LIGHTNING;
                    } else {
                        // A record from a rail this build can no longer pay (a
                        // card quote from before the rail was removed) and never
                        // issued: nothing to resume. A card quote that DID pay
                        // carries a sig and takes the redeem-only path above.
                        PendingPurchase.clear(appContext);
                        fetchOptions();
                        return;
                    }
                    post(gen, UiState.pay(payPhase, pending.amountCents, pending.denomGbMonths,
                            pending.sizeGb, pending.durationMonths, pending.payRequest,
                            pending.address, pending.amountSats, pending.minConfirmations, false));
                }
                completePurchase(gen, purchase, session, id, pending.sizeGb, pending.durationMonths,
                        pending.method, pending);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                post(gen, UiState.error(errorText(e)));
            } finally {
                SyncSecrets.wipe(code);
            }
        });
        return true;
    }

    /** Abandons the current pay/poll and returns to the denomination picker. The
     *  in-flight loop bails on the generation bump; any already-paid credit is not
     *  redeemed here (the user chose to go back). */
    public void backToPick() {
        flowGen++; // stop any running poll loop
        if (!cachedOptions.isEmpty()) {
            state.setValue(UiState.pick(cachedOptions, cachedMethods));
        } else {
            fetchOptions();
        }
    }

    /**
     * The on-chain screen's explicit "Cancel this payment": drops the persisted
     * record — the ONLY door out for an on-chain quote, which is marked submitted
     * from the start (see {@link #startPurchase}) so neither Back nor leaving the
     * wizard can discard it. Reached only through the fragment's confirmation
     * dialog, whose copy states the consequence: bitcoin already sent to the
     * address cannot be attributed to a credit afterwards (the mint never learns
     * a return address, so there is no refund path on this rail).
     */
    public void cancelPendingPurchase() {
        flowGen++; // stop the poll before the record goes
        paymentSubmitted = false;
        CreditSettleWorker.cancel(appContext);
        Future<?> running = flowFuture;
        if (running != null) {
            // Wake a sleeping poll so it sees the gen bump now, not in 30 s.
            // OkHttp's socket reads don't observe the interrupt, so an ask in
            // flight completes normally; its result is handled as before (a
            // credit the mint DID issue is still booked), and the clear queued
            // next runs after the flow returns — the single thread keeps that
            // order, the interrupt only shortens the wait.
            running.cancel(true);
        }
        executor.execute(() -> PendingPurchase.clear(appContext));
        if (!cachedOptions.isEmpty()) {
            state.setValue(UiState.pick(cachedOptions, cachedMethods));
        } else {
            fetchOptions();
        }
    }

    /** Re-fetches the denominations after an error (the guard in loadOptions()
     *  proceeds because the current phase is ERROR). */
    public void retry() {
        loadOptions();
    }

    // ---- Nostr Wallet Connect (pay from the user's own connected wallet) ----

    public LiveData<WalletPay> getWalletPay() {
        return walletPay;
    }

    /** Reason for the last failed wallet payment, for the caller's status line. */
    @Nullable
    public String getWalletPayError() {
        return walletPayError;
    }

    /**
     * Asks the user's connected wallet to pay the current Lightning invoice.
     *
     * <p><b>Only ever called from an explicit tap.</b> An app that spends money
     * because a screen appeared would be indefensible, so there is no auto-fire
     * on entering the pay stage, and no retry loop — one tap, one payment
     * attempt.
     *
     * <p>The purchase itself is NOT completed here. On success this returns to
     * IDLE-ish SENT and the ALREADY-RUNNING settlement poll (started when the
     * quote was created) observes the payment and drives the state machine to
     * SUCCESS — the identical path a QR payment takes. That is what keeps this
     * a shortcut rather than a second, parallel purchase implementation.
     */
    public void payWithConnectedWallet() {
        UiState current = state.getValue();
        if (current == null || current.phase != Phase.PAY_LIGHTNING || current.payRequest == null) {
            return;
        }
        if (walletPay.getValue() == WalletPay.PAYING) {
            return; // one attempt at a time; a second tap must not double-spend
        }
        final String invoice = current.payRequest;
        final int gen = flowGen;
        walletPayError = null;
        walletPay.setValue(WalletPay.PAYING);

        executor.execute(() -> {
            String error = null;
            boolean sent = false;
            try {
                NwcUri connection = new NwcWallet(appContext).load();
                if (connection == null) {
                    error = appContext.getString(R.string.buy_credit_wallet_gone);
                } else {
                    new NwcClient(connection, http).payInvoice(invoice);
                    sent = true;
                }
            } catch (NwcClient.WalletException e) {
                error = walletErrorMessage(e);
            } catch (IOException | RuntimeException e) {
                // Includes the timeout. A timeout is NOT a proven failure — the
                // wallet may settle after we stop listening — so the copy says
                // "couldn't confirm" and the poll keeps running. Never phrase
                // this as "payment failed": that invites a second payment for a
                // credit the user may already own.
                error = appContext.getString(R.string.buy_credit_wallet_unconfirmed);
            }
            final String finalError = error;
            final boolean finalSent = sent;
            main.post(() -> {
                if (gen != flowGen) {
                    // The user left the pay screen (or retried) while the wallet
                    // was thinking; don't paint a stale result over a new flow.
                    walletPay.setValue(WalletPay.IDLE);
                    return;
                }
                walletPayError = finalError;
                walletPay.setValue(finalSent ? WalletPay.SENT : WalletPay.FAILED);
                if (finalSent) {
                    // The wallet says the invoice is paid — money in flight, so
                    // the record must survive leaving the wizard (same rule as
                    // the Checkout success redirect).
                    markPaymentSubmitted();
                }
            });
        });
    }

    /**
     * Maps a NIP-47 error code to copy that says what the user can DO. The
     * codes are a small, stable, spec-defined set, so this is a switch rather
     * than a message passthrough — a raw wallet string is usually English-only
     * and frequently developer-facing.
     */
    private String walletErrorMessage(NwcClient.WalletException e) {
        switch (e.code) {
            case "INSUFFICIENT_BALANCE":
                return appContext.getString(R.string.buy_credit_wallet_no_balance);
            case "QUOTA_EXCEEDED":
                return appContext.getString(R.string.buy_credit_wallet_over_budget);
            case "RESTRICTED":
            case "UNAUTHORIZED":
                return appContext.getString(R.string.buy_credit_wallet_not_allowed);
            case "PAYMENT_FAILED":
                return appContext.getString(R.string.buy_credit_wallet_route_failed);
            default:
                return appContext.getString(R.string.buy_credit_wallet_unconfirmed);
        }
    }

    /** Clears a shown wallet result so re-entering the stage starts clean. */
    public void clearWalletPay() {
        walletPayError = null;
        walletPay.setValue(WalletPay.IDLE);
    }

    /**
     * Records that the user SUBMITTED the payment for the current flow — called
     * when the connected wallet reports the invoice paid (the on-chain rail
     * marks its record at creation instead, see {@link #startPurchase}). Money
     * is now plausibly in flight even though issue hasn't confirmed it, so the
     * persisted record (the only copy of the blinding secret) is marked and
     * {@link #onCleared}'s abandon-cleanup will no longer drop it: with a
     * lagging settlement, backing out of "Waiting for payment…" used to clear
     * the sig-less record and destroy a credit whose payment later settled.
     *
     * <p>Persisted on its OWN short-lived thread, not the flow executor — the
     * poll loop occupies that single thread for up to the whole poll budget, so
     * a queued write would land minutes late (or never, before a kill). The
     * write can race the poll's own {@code withSig} save benignly in both
     * orders: sig-beats-submitted leaves a sig-bearing record (already kept by
     * the cleanup), and submitted-beats-sig re-saves a pre-sig record whose
     * resume re-issues the SAME blinded message — the mint's replay path
     * returns the cached signature.
     */
    public void markPaymentSubmitted() {
        paymentSubmitted = true;
        // Money in flight the wizard may not live to see settle — arm the
        // background settler for the Lightning wallet-paid case too.
        CreditSettleWorker.schedule(appContext);
        new Thread(() -> {
            PendingPurchase pending = PendingPurchase.load(appContext);
            if (pending != null && !pending.submitted) {
                pending.withSubmitted().save(appContext);
            }
        }, "purchase-submitted").start();
    }

    @Override
    protected void onCleared() {
        flowGen++; // signal any running loop to stop
        // Leaving the wizard (system back, toolbar Up, or the activity finishing —
        // but NOT a config change or process death, neither of which calls
        // onCleared) ABANDONS an unpaid attempt: drop the persisted pending so the
        // NEXT entry starts at the picker instead of resumePendingIfAny() jumping
        // straight back into an abandoned invoice (the "every re-entry goes to
        // the pay screen" bug). NEVER drop a sig-bearing record — that is a
        // paid-but-unredeemed credit (real money) and must still be redeemed on the
        // next entry — and NEVER a SUBMITTED one either (every record is marked
        // submitted the moment its pay UI exists, since an address or invoice
        // can be paid from any wallet with no signal back here; sig-less is
        // NOT the same as unpaid — see startPurchase; in practice only a record
        // that never reached its pay screen is dropped here). An INVOLUNTARY
        // interruption (process death) keeps everything too, since onCleared isn't
        // called then. Queued on the single-thread executor so it runs AFTER the
        // running poll bails on the flowGen bump — race-free against the poll's
        // own sig-persist — and shutdown() (NOT shutdownNow) lets that queued
        // clear run instead of being discarded as never-started. The volatile
        // paymentSubmitted covers the window where the marker's own persist
        // thread hasn't landed yet.
        executor.execute(() -> {
            PendingPurchase pending = PendingPurchase.load(appContext);
            if (pending != null && (pending.sigHex == null || pending.sigHex.isEmpty())
                    && !pending.submitted && !paymentSubmitted) {
                PendingPurchase.clear(appContext);
            }
        });
        executor.shutdown();
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

    /**
     * Whether a mint issue failure proves the quote can never yield a credit, so
     * the persisted purchase record (the only copy of the blinding secret) may be
     * dropped. ONLY the slug that means "no money was ever taken" qualifies: an
     * expired UNPAID quote (the mint never expires a paid one, and an on-chain
     * quote with a payment in sight has its expiry pushed out). Every other
     * fatal — including a 409 and anything unrecognised — keeps the record,
     * because if the payment did settle, clearing it destroys a credit the user
     * paid for and nothing can recover it. (A refunded slug used to qualify too;
     * it went with the card rail — neither Bitcoin rail can refund.)
     */
    /** {@code quote-expired} is the one slug that proves no money is owed —
     *  for LIGHTNING. An on-chain 410 is only "nothing seen YET"; see the
     *  late-window rule where this is consulted. */
    private static boolean isDeadQuote(String slug) {
        return MintClient.SLUG_QUOTE_EXPIRED.equals(slug);
    }

    /**
     * The copy for a failure, switched on the mint's SLUG through
     * {@link PurchaseError} — never the HTTP status. {@code rail-unavailable}
     * shows the server's own {@code detail} (it is written for the user and
     * says what to do: "…while the Bitcoin node syncs. Pay with Lightning…");
     * only {@code rate-limited}/{@code server-busy} get the "too many requests"
     * copy, which every 503 used to get.
     */
    private String errorText(Exception e) {
        PurchaseError err = PurchaseError.classify(e);
        switch (err.kind) {
            case BUSY:
                return appContext.getString(R.string.buy_credit_error_busy);
            case RAIL_UNAVAILABLE:
                return err.detail != null ? err.detail
                        : appContext.getString(R.string.buy_credit_error_rail_unavailable);
            case QUOTE_EXPIRED:
                return appContext.getString(R.string.buy_credit_error_expired);
            case OTHER_SLUG:
                return appContext.getString(R.string.buy_credit_error_slug, err.slug);
            default:
                return appContext.getString(R.string.buy_credit_error_network);
        }
    }
}
