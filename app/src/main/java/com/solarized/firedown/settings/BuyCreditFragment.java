package com.solarized.firedown.settings;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.BuyCreditViewModel;
import com.solarized.firedown.nwc.NwcClient;
import com.solarized.firedown.nwc.NwcUri;
import com.solarized.firedown.nwc.NwcWallet;
import com.solarized.firedown.phone.fragments.P2pScanFragment;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.utils.QrCodes;

import org.json.JSONObject;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * The "Add storage credit" purchase wizard — a single full-page nav destination
 * (like {@link com.solarized.firedown.phone.fragments.P2pSendFragment}, whose
 * QR handover also wants a whole page) that switches between step views under
 * one {@link BuyCreditViewModel}: pick denomination + rail → pay (Lightning QR /
 * Stripe hosted Checkout in a tab) → success. No Stripe SDK — the card path opens
 * the mint's hosted Checkout URL in a browser tab and polls the same
 * {@code /v1/mint/issue} as Lightning.
 */
@AndroidEntryPoint
public class BuyCreditFragment extends Fragment {

    /** For the current backed-up footprint behind the over-cap honesty line. */
    @Inject
    CloudBackupManager mCloudBackup;

    /** Shared client; NwcClient derives its own websocket-shaped copy from it. */
    @Inject
    OkHttpClient mHttp;

    /** One thread for the connect probe (a relay round trip). Not the
     *  ViewModel's executor: that one serializes the purchase flow, and a
     *  connect attempt must never sit behind a settlement poll. */
    private final ExecutorService mConnectExecutor = Executors.newSingleThreadExecutor();

    private BuyCreditViewModel mViewModel;
    private NavController mNavController;

    // Step containers (only one visible at a time).
    private View mStepLoading;
    private View mStepPick;
    private View mStepLightning;
    private View mStepStripe;
    private View mStepSuccess;
    private View mStepError;

    // Pick step.
    private ViewGroup mDenomContainer;
    private MaterialButtonToggleGroup mRailGroup;
    private MaterialButton mContinue;
    // Plan-grid views (hidden in the legacy flat-list mode).
    private View mDurationSection;
    private MaterialButtonToggleGroup mDurationToggle;
    private TextView mSizeLabel;
    private View mGbmExplainer;
    private View mSoftcapNote;
    private TextView mFootprintNote;
    private View mOneOffNote;
    /** Backed-up bytes on the account (-1 = unknown), feeding the over-cap
     *  honesty line: seeded from the status cache, refreshed by loadStatus. */
    private long mFootprintBytes = -1;
    /** The chosen tile/denomination (an Option), or null until one is selected. */
    private BuyCreditViewModel.Option mSelectedOption;
    /** In the grid, the size the user last picked, so switching duration keeps the
     *  same size row selected (only the price changes) instead of snapping back. */
    private int mPreferredSizeGb = -1;
    /** The current plan options, so a duration change can rebuild the size tiles. */
    private List<BuyCreditViewModel.Option> mPlanOptions = Collections.emptyList();
    private String mSelectedRail = BuyCreditViewModel.RAIL_LIGHTNING;

    // Lightning / Stripe pay state.
    private String mPayRequest;
    private String mCheckoutUrl;
    /** So entering the Stripe step doesn't re-load Checkout on every re-render
     *  (config change, poll tick). */
    private boolean mCheckoutOpened;
    /** The embedded hosted-Checkout WebView, created lazily into
     *  {@code buy_stripe_web_container} — NEVER inflated from XML, so a device
     *  with no WebView provider (possible on the de-Googled devices this browser
     *  targets) falls back to the browser-tab flow instead of crashing the whole
     *  wizard. Null until the Stripe step is first shown, or permanently when
     *  creation failed ({@link #mStripeWebFailed}). */
    private WebView mStripeWeb;
    private boolean mStripeWebFailed;
    /** One-shot: the warm-up page (preconnects to Stripe's hosts) was loaded. */
    private boolean mStripeWebWarmed;
    /** Spinner overlaid on the Checkout WebView until its first real paint. */
    private ProgressBar mStripeWebProgress;

    /**
     * Warm-up page for the embedded Checkout: creating the WebView here pays the
     * one-off Chromium provider init while the user is still on the picker, and
     * the preconnect hints open DNS+TLS to Stripe's hosts so the real
     * checkout_url (which doesn't exist until the mint quotes) starts on warm
     * connections. Loading this costs nothing user-visible — the Stripe step is
     * hidden until the PAY_STRIPE phase.
     */
    private static final String STRIPE_WARMUP_HTML = "<html><head>"
            + "<link rel=\"preconnect\" href=\"https://checkout.stripe.com\">"
            + "<link rel=\"preconnect\" href=\"https://js.stripe.com\">"
            + "<link rel=\"dns-prefetch\" href=\"https://m.stripe.network\">"
            + "</head><body></body></html>";

    /** Intercepts Back on a pay screen to return to the picker (stops polling);
     *  enabled only while a pay screen is shown. */
    private OnBackPressedCallback mPayBack;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_buy_credit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mNavController = NavHostFragment.findNavController(this);
        mViewModel = new ViewModelProvider(this).get(BuyCreditViewModel.class);

        mStepLoading = view.findViewById(R.id.buy_step_loading);
        mStepPick = view.findViewById(R.id.buy_step_pick);
        mStepLightning = view.findViewById(R.id.buy_step_lightning);
        mStepStripe = view.findViewById(R.id.buy_step_stripe);
        mStepSuccess = view.findViewById(R.id.buy_step_success);
        mStepError = view.findViewById(R.id.buy_step_error);

        mDenomContainer = view.findViewById(R.id.buy_denom_container);
        mRailGroup = view.findViewById(R.id.buy_rail_group);
        mContinue = view.findViewById(R.id.buy_continue);
        mDurationSection = view.findViewById(R.id.buy_duration_section);
        mDurationToggle = view.findViewById(R.id.buy_duration_toggle);
        mSizeLabel = view.findViewById(R.id.buy_size_label);
        mGbmExplainer = view.findViewById(R.id.buy_gbm_explainer);
        mSoftcapNote = view.findViewById(R.id.buy_softcap_note);
        mFootprintNote = view.findViewById(R.id.buy_footprint_note);
        mOneOffNote = view.findViewById(R.id.buy_oneoff_note);

        // Current footprint for the over-cap honesty line: paint from the
        // status cache immediately, then refresh (a stale footprint only
        // mis-sizes an advisory line, never the purchase itself).
        CloudBackupManager.Status cached = mCloudBackup.lastStatus();
        if (cached != null && cached.totalBytes >= 0) {
            mFootprintBytes = cached.totalBytes;
        }
        mCloudBackup.loadStatus(status -> {
            if (!isAdded()) {
                return;
            }
            if (status.totalBytes >= 0) {
                mFootprintBytes = status.totalBytes;
            }
            updateFootprintNote(mSelectedOption);
        });

        // Changing the duration rebuilds the size tiles for that coverage (each
        // duration is priced by its own keysets). The button's tag is its months.
        mDurationToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            View btn = group.findViewById(checkedId);
            if (btn != null && btn.getTag() instanceof Integer) {
                buildSizeTiles((Integer) btn.getTag());
            }
        });

        // List scrolls under the nav bar; the last element clears it (same inset
        // treatment as the other settings sub-screens).
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mRailGroup.check(R.id.buy_rail_lightning);
        mRailGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            mSelectedRail = checkedId == R.id.buy_rail_card
                    ? BuyCreditViewModel.RAIL_STRIPE : BuyCreditViewModel.RAIL_LIGHTNING;
            if (BuyCreditViewModel.RAIL_STRIPE.equals(mSelectedRail)) {
                // Take the two client-side costs of "Continue" off the critical
                // path while the user is still deciding: posted to the next
                // frame so the segment's own check animation isn't janked by
                // the (one-off, process-wide) Chromium provider init.
                view.post(this::warmStripeWebView);
            }
        });

        mContinue.setOnClickListener(v -> {
            if (mSelectedOption != null) {
                mCheckoutOpened = false;
                mViewModel.startPurchase(mSelectedOption, mSelectedRail);
            }
        });

        // Lightning pay actions.
        view.findViewById(R.id.buy_ln_wallet_pay).setOnClickListener(
                v -> mViewModel.payWithConnectedWallet());
        view.findViewById(R.id.buy_ln_wallet_link).setOnClickListener(v -> showWalletDialog());
        view.findViewById(R.id.buy_ln_open_wallet).setOnClickListener(v -> openInWallet());
        view.findViewById(R.id.buy_ln_copy).setOnClickListener(v -> copyToClipboard(
                getString(R.string.buy_credit_ln_invoice_label), mPayRequest,
                getString(R.string.buy_credit_ln_copied)));

        // Stripe pay actions.
        view.findViewById(R.id.buy_stripe_reopen).setOnClickListener(v -> openCheckout());

        // Success actions.
        view.findViewById(R.id.buy_done).setOnClickListener(v -> mNavController.popBackStack());
        view.findViewById(R.id.buy_backup_more).setOnClickListener(v -> mNavController.popBackStack());

        // Error retry.
        view.findViewById(R.id.buy_error_retry).setOnClickListener(v -> mViewModel.retry());

        // A pay screen's Back returns to the picker (and stops polling) instead of
        // leaving the wizard; elsewhere Back leaves normally (disabled by default,
        // enabled only while a pay screen is shown).
        mPayBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                mViewModel.backToPick();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), mPayBack);

        mViewModel.getState().observe(getViewLifecycleOwner(), this::render);
        mViewModel.getWalletPay().observe(getViewLifecycleOwner(), this::renderWalletPay);
        // Registered here rather than when the scanner opens, so a result still
        // lands after a config change or process death while it was up (the
        // SyncSettingsFragment pattern).
        observeWalletScanResult();
        mViewModel.loadOptions();
    }

    @Override
    public void onDestroyView() {
        // The embedded Checkout WebView is view-scoped — destroy it with the view
        // or it leaks its window callbacks and keeps its renderer alive.
        if (mStripeWeb != null) {
            mStripeWeb.destroy();
            mStripeWeb = null;
        }
        mStripeWebProgress = null;
        mStripeWebWarmed = false;
        super.onDestroyView();
    }

    private void render(BuyCreditViewModel.UiState s) {
        showStep(s.phase);
        switch (s.phase) {
            case PICK -> bindPick(s);
            case PAY_LIGHTNING -> bindLightning(s);
            case PAY_STRIPE -> bindStripe(s);
            case SUCCESS -> bindSuccess(s);
            case ERROR -> bindError(s);
            default -> { /* LOADING_OPTIONS / STARTING — spinner only */ }
        }
    }

    private void showStep(BuyCreditViewModel.Phase phase) {
        mStepLoading.setVisibility(phase == BuyCreditViewModel.Phase.LOADING_OPTIONS
                || phase == BuyCreditViewModel.Phase.STARTING ? View.VISIBLE : View.GONE);
        mStepPick.setVisibility(phase == BuyCreditViewModel.Phase.PICK ? View.VISIBLE : View.GONE);
        mStepLightning.setVisibility(phase == BuyCreditViewModel.Phase.PAY_LIGHTNING ? View.VISIBLE : View.GONE);
        mStepStripe.setVisibility(phase == BuyCreditViewModel.Phase.PAY_STRIPE ? View.VISIBLE : View.GONE);
        mStepSuccess.setVisibility(phase == BuyCreditViewModel.Phase.SUCCESS ? View.VISIBLE : View.GONE);
        mStepError.setVisibility(phase == BuyCreditViewModel.Phase.ERROR ? View.VISIBLE : View.GONE);
        if (phase != BuyCreditViewModel.Phase.PAY_STRIPE) {
            // Leaving the card step (back to picker, success, error): drop the
            // Checkout page so a hidden WebView isn't left running the session.
            hideStripeWeb();
        }
    }

    // ---- pick ----

    private void bindPick(BuyCreditViewModel.UiState s) {
        mSelectedOption = null;
        mContinue.setEnabled(false);
        // The picker's Back must LEAVE the wizard. The pay screens enable
        // mPayBack (Back → backToPick); returning to PICK from a pay screen
        // re-runs bindPick, so it must disable it again — otherwise Back on the
        // picker just calls backToPick() while already on PICK and does nothing
        // (the "back from plan does nothing / stuck" bug). Only the pay screens
        // enable it; every other phase disables it.
        setPayBackEnabled(false);
        // Plan-grid mode when the server advertises (size × duration) tiles; else
        // the legacy flat denomination list (BuyCreditViewModel already returns
        // only one kind at a time).
        boolean anyPlan = false;
        for (BuyCreditViewModel.Option o : s.options) {
            if (o.isPlan()) {
                anyPlan = true;
                break;
            }
        }
        if (anyPlan) {
            bindPickGrid(s.options);
        } else {
            bindPickLegacy(s.options);
        }
    }

    // ---- plan grid (duration toggle × size tiles) ----

    private void bindPickGrid(List<BuyCreditViewModel.Option> options) {
        mPlanOptions = options;
        mSizeLabel.setText(R.string.buy_credit_plan_size_label);
        mGbmExplainer.setVisibility(View.GONE);
        mSoftcapNote.setVisibility(View.VISIBLE);
        mOneOffNote.setVisibility(View.VISIBLE);

        // Distinct durations, in the ascending order the options already carry.
        List<Integer> durations = new ArrayList<>();
        for (BuyCreditViewModel.Option o : options) {
            if (!durations.contains(o.durationMonths)) {
                durations.add(o.durationMonths);
            }
        }

        // Build the "Keep my backups for" toggle (hidden when only one duration is
        // for sale — the tiles still say "for <duration>"). Each longer duration
        // carries its discount as a BADGE on the segment itself ("1 year  −25%"),
        // computed against the shortest duration's best per-GB-month rate — the
        // LNClear pattern: the saving is visible BEFORE any selection, attached
        // to the option it applies to. This replaced the selection-dependent
        // savings text line below the toggle, which needed two rounds of fixes
        // (vanishing on the best plan, layout jumps) precisely because it only
        // existed after a selection; a static per-catalog badge can't do either.
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        mDurationToggle.removeAllViews();
        List<Integer> buttonIds = new ArrayList<>();
        int baseMonths = durations.isEmpty() ? 0 : durations.get(0);
        for (int months : durations) {
            MaterialButton btn = (MaterialButton) inflater.inflate(
                    R.layout.item_buy_duration_button, mDurationToggle, false);
            int id = View.generateViewId();
            btn.setId(id);
            btn.setTag(months);
            btn.setText(durationLabelWithBadge(months, baseMonths));
            mDurationToggle.addView(btn);
            buttonIds.add(id);
        }
        mDurationSection.setVisibility(durations.size() > 1 ? View.VISIBLE : View.GONE);

        // Default to the middle duration (e.g. 1 year of 1 mo / 1 yr / 2 yr) —
        // checking it fires the listener, which builds that duration's size tiles
        // AND updates the save nudge for the selected duration.
        int defaultDuration = durations.size() >= 3 ? 1 : 0;
        mDurationToggle.check(buttonIds.get(defaultDuration));
    }

    /** (Re)builds the size tiles for the chosen coverage. Keeps the previously
     *  picked size selected across a duration switch when that size still exists. */
    private void buildSizeTiles(int durationMonths) {
        mDenomContainer.removeAllViews();
        mSelectedOption = null;
        mContinue.setEnabled(false);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        // "for <duration>" on a tile is redundant when the duration toggle is
        // visible (it already says it) — show it only in the single-duration case
        // where the toggle is hidden, so the coverage is still stated somewhere.
        boolean showFor = mDurationSection.getVisibility() != View.VISIBLE;
        int count = 0;
        MaterialCardView preferred = null;
        BuyCreditViewModel.Option preferredOpt = null;
        List<MaterialCardView> cards = new ArrayList<>();
        List<BuyCreditViewModel.Option> tileOpts = new ArrayList<>();
        for (BuyCreditViewModel.Option opt : mPlanOptions) {
            if (opt.durationMonths != durationMonths) {
                continue;
            }
            MaterialCardView card = (MaterialCardView) inflater.inflate(
                    R.layout.item_buy_credit_plan, mDenomContainer, false);
            ((TextView) card.findViewById(R.id.buy_plan_size))
                    .setText(getString(R.string.buy_credit_plan_size, opt.sizeGb));
            TextView forLabel = card.findViewById(R.id.buy_plan_for);
            if (showFor) {
                forLabel.setText(getString(R.string.buy_credit_plan_for, formatDuration(durationMonths)));
            } else {
                forLabel.setVisibility(View.GONE);
            }
            ((TextView) card.findViewById(R.id.buy_plan_price)).setText(formatUsd(opt.priceCents));
            // Per-month equivalent under the one-time price so tiers are
            // comparable without mental math (and the save nudge verifiable).
            // Hidden on 1-month plans, where it would just repeat the price.
            TextView perMonth = card.findViewById(R.id.buy_plan_permonth);
            if (durationMonths > 1) {
                perMonth.setText(getString(R.string.buy_credit_per_month,
                        formatUsd(Math.round((double) opt.priceCents / durationMonths))));
            } else {
                perMonth.setVisibility(View.GONE);
            }
            card.setTag(opt);
            announceCheckable(card);
            card.setOnClickListener(v -> selectCard(card, opt));
            mDenomContainer.addView(card);
            cards.add(card);
            tileOpts.add(opt);
            if (opt.sizeGb == mPreferredSizeGb) {
                preferred = card;
                preferredOpt = opt;
            }
            count++;
        }
        if (count == 0) {
            return;
        }
        if (preferred != null) {
            selectCard(preferred, preferredOpt);
        } else {
            int idx = count >= 3 ? 1 : 0; // middle size by default
            selectCard(cards.get(idx), tileOpts.get(idx));
        }
    }

    /**
     * The duration segment's label, with a "−N%" discount badge appended when
     * this duration is cheaper than the SHORTEST duration (the baseline
     * everyone anchors on). Smaller + primary-colored + bold so it reads as a
     * tag, not part of the label; localized via the percent formatter (Turkish
     * prefixes the sign/percent, etc.). Badges under 5% are noise and skipped.
     *
     * <p>The percentage is LIKE-FOR-LIKE and never overstated: the MINIMUM
     * per-size saving across sizes sold in BOTH durations. The original
     * best-rate-vs-best-rate comparison read "−40%" on a catalog whose cheapest
     * yearly unit came from a 200 GB tile with NO 3-month counterpart — a
     * saving only reachable by ALSO upsizing, while a like-for-like buyer got
     * −10% (50 GB) or −25% (100 GB); on-device screenshot report. Min (not
     * max) so an uneven ladder can only ever UNDERSTATE the saving — on a
     * uniform-discount catalog (the runbook's minted ladder) min == max ==
     * exact for every buyer.
     */
    private CharSequence durationLabelWithBadge(int months, int baseMonths) {
        String label = formatDuration(months);
        if (months == baseMonths) {
            return label;
        }
        double worst = -1; // the smallest like-for-like saving across common sizes
        for (BuyCreditViewModel.Option o : mPlanOptions) {
            if (o.durationMonths != months || o.denomGbMonths <= 0) {
                continue;
            }
            for (BuyCreditViewModel.Option base : mPlanOptions) {
                if (base.durationMonths != baseMonths || base.sizeGb != o.sizeGb
                        || base.denomGbMonths <= 0) {
                    continue;
                }
                double saving = 1.0 - ((double) o.priceCents / o.denomGbMonths)
                        / ((double) base.priceCents / base.denomGbMonths);
                if (worst < 0 || saving < worst) {
                    worst = saving;
                }
            }
        }
        int pct = (int) Math.round(worst * 100.0);
        if (worst <= 0 || pct < 5) {
            return label;
        }
        String badge = NumberFormat.getPercentInstance(Locale.getDefault()).format(-pct / 100.0);
        SpannableString text = new SpannableString(label + "  " + badge);
        int start = label.length() + 2;
        // NO ForegroundColorSpan — the badge INHERITS the button's own
        // @color/buy_segment_text state list, so it follows the check state for
        // free. It used to be pinned to colorPrimary here, resolved ONCE at
        // build time and never re-evaluated, which made it invisible the moment
        // its own segment was checked: coral badge on the checked fill was
        // 1.07:1 in light theme and 1.22:1 in dark (and only 1.75/1.56 against
        // the older container-toned fill — it was never really legible there
        // either). A span needs a concrete int, so a state-aware colour would
        // mean re-setting every segment's label from a check listener; the
        // badge doesn't need colour to read as a badge. Bold + 0.82x carries it,
        // and inheriting cannot desync.
        text.setSpan(new RelativeSizeSpan(0.82f), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    // ---- legacy flat denomination list ----

    private void bindPickLegacy(List<BuyCreditViewModel.Option> options) {
        mDurationSection.setVisibility(View.GONE);
        mSoftcapNote.setVisibility(View.GONE);
        mOneOffNote.setVisibility(View.GONE);
        mSizeLabel.setText(R.string.buy_credit_pick_how_much);
        mGbmExplainer.setVisibility(View.VISIBLE);
        mDenomContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (BuyCreditViewModel.Option opt : options) {
            MaterialCardView card = (MaterialCardView) inflater.inflate(
                    R.layout.item_buy_credit_denom, mDenomContainer, false);
            TextView amt = card.findViewById(R.id.buy_denom_amt);
            TextView price = card.findViewById(R.id.buy_denom_price);
            TextView rate = card.findViewById(R.id.buy_denom_rate);
            amt.setText(getString(R.string.buy_credit_denom_amount, opt.denomGbMonths));
            price.setText(formatUsd(opt.priceCents));
            rate.setText(getString(R.string.buy_credit_denom_rate, formatPerGbMonth(opt)));
            card.setTag(opt);
            announceCheckable(card);
            card.setOnClickListener(v -> selectCard(card, opt));
            mDenomContainer.addView(card);
        }
        int defaultIndex = options.size() >= 3 ? 1 : 0;
        if (mDenomContainer.getChildCount() > defaultIndex) {
            MaterialCardView def = (MaterialCardView) mDenomContainer.getChildAt(defaultIndex);
            selectCard(def, (BuyCreditViewModel.Option) def.getTag());
        }
    }

    /** Highlights the chosen card and enables Continue. Shared by the plan tiles
     *  and the legacy denomination cards. */
    private void selectCard(MaterialCardView selected, BuyCreditViewModel.Option opt) {
        mSelectedOption = opt;
        if (opt.isPlan()) {
            mPreferredSizeGb = opt.sizeGb;
        }
        // Selection is a PRIMARY (coral) stroke — it ties the chosen plan to the
        // "Continue · $X" button visually (maintainer's call, reversing the
        // earlier neutral-outline stance). STROKE ONLY still stands: do NOT
        // switch this to MaterialCardView's checkable/checked state — its checked
        // foreground layer tints the whole tile with colorPrimary and painted it
        // a muddy pink in both themes (rejected on-device). The plain
        // view-selected flag below carries the state for TalkBack instead.
        // colorPrimary lives in appcompat's R (the material R only holds the
        // M3-specific attrs like colorOutlineVariant below) — same attr XML's
        // ?attr/colorPrimary resolves.
        int selectedColor = MaterialColors.getColor(selected, androidx.appcompat.R.attr.colorPrimary);
        int outline = MaterialColors.getColor(selected, com.google.android.material.R.attr.colorOutlineVariant);
        int stroke = Math.round(getResources().getDisplayMetrics().density);
        for (int i = 0; i < mDenomContainer.getChildCount(); i++) {
            MaterialCardView card = (MaterialCardView) mDenomContainer.getChildAt(i);
            boolean on = card == selected;
            card.setSelected(on);
            card.setStrokeColor(on ? selectedColor : outline);
            card.setStrokeWidth(on ? stroke * 2 : stroke);
        }
        mContinue.setText(getString(R.string.buy_credit_continue, formatUsd(opt.priceCents)));
        mContinue.setEnabled(true);
        updateFootprintNote(opt);
    }

    /**
     * The over-cap honesty line: when the account already stores MORE than the
     * selected tile's cap, the tile's duration label is a lie for THIS user
     * (600 GB-months at a 200 GB footprint is ~3 months, not "1 year"), so
     * state what the credit really buys: "You currently store X — at that size
     * this credit lasts about N months". Visibility three-ways: GONE while no
     * tile of the catalog is below the footprint (the common under-cap user
     * pays no dead space), INVISIBLE for an under-cap tile once any over-cap
     * tile exists (holds the space so toggling tiles doesn't jump the layout —
     * the save-nudge lesson), VISIBLE with the numbers otherwise.
     */
    private void updateFootprintNote(BuyCreditViewModel.Option opt) {
        if (mFootprintNote == null) {
            return;
        }
        double storedGb = mFootprintBytes > 0 ? mFootprintBytes / 1_000_000_000.0 : -1;
        boolean anyOverCap = false;
        if (storedGb > 0) {
            for (BuyCreditViewModel.Option o : mPlanOptions) {
                if (o.isPlan() && storedGb > o.sizeGb) {
                    anyOverCap = true;
                    break;
                }
            }
        }
        if (!anyOverCap) {
            mFootprintNote.setVisibility(View.GONE);
            return;
        }
        if (opt == null || !opt.isPlan() || storedGb <= opt.sizeGb || opt.denomGbMonths <= 0) {
            mFootprintNote.setVisibility(View.INVISIBLE);
            return;
        }
        int months = (int) Math.max(1, Math.round(opt.denomGbMonths / storedGb));
        mFootprintNote.setText(getString(R.string.buy_credit_footprint_note,
                Formatter.formatShortFileSize(requireContext(), mFootprintBytes),
                formatDuration(months)));
        mFootprintNote.setVisibility(View.VISIBLE);
    }

    /** Exposes a tile's selected state to accessibility services — without this
     *  TalkBack reads every tile identically and the selection is invisible to a
     *  non-sighted user (Continue's price is the only tell). Reads the plain
     *  view-selected flag, NOT MaterialCardView's Checkable state — the card is
     *  deliberately not checkable (its checked foreground layer is
     *  colorPrimary-tinted, the rejected pink wash). */
    private static void announceCheckable(MaterialCardView card) {
        ViewCompat.setAccessibilityDelegate(card, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
    }

    // ---- lightning ----

    private void bindLightning(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(true);
        bindWalletControls();
        mPayRequest = s.payRequest;
        ((TextView) requireView().findViewById(R.id.buy_ln_amount)).setText(payAmountText(s));
        TextView invoice = requireView().findViewById(R.id.buy_ln_invoice);
        invoice.setText(s.payRequest);
        ImageView qr = requireView().findViewById(R.id.buy_ln_qr);
        if (s.payRequest != null) {
            // Uppercase for the QR alphanumeric mode (a BOLT11 is case-insensitive).
            Bitmap bmp = QrCodes.encode("lightning:" + s.payRequest.toUpperCase(Locale.ROOT));
            if (bmp != null) {
                qr.setImageBitmap(bmp);
            }
        }
    }

    // ---- Nostr Wallet Connect (pay from a connected wallet) ----

    /**
     * Paints the connected-wallet controls for the current stage.
     *
     * <p>Two audiences, and the split is deliberate: a user WITH a wallet gets
     * a full-width pay button leading the stage, because it is the one action
     * that finishes the purchase without leaving the screen. A user WITHOUT one
     * sees only the quiet link near the bottom — the QR keeps the position it
     * always had, and handing an app a spending key stays an opt-in nobody is
     * nudged into.
     */
    private void bindWalletControls() {
        View root = getView();
        if (root == null) {
            return;
        }
        String label = new NwcWallet(requireContext()).label();
        MaterialButton pay = root.findViewById(R.id.buy_ln_wallet_pay);
        MaterialButton link = root.findViewById(R.id.buy_ln_wallet_link);
        TextView status = root.findViewById(R.id.buy_ln_wallet_status);

        boolean connected = label != null;
        pay.setVisibility(connected ? View.VISIBLE : View.GONE);
        link.setText(connected
                ? getString(R.string.buy_credit_wallet_manage_link)
                : getString(R.string.buy_credit_wallet_connect_link));
        // The status line defaults to naming the wallet, so a user can see
        // WHICH wallet is about to be charged before tapping. renderWalletPay
        // overwrites it while an attempt is in flight.
        if (connected && mViewModel.getWalletPay().getValue() == BuyCreditViewModel.WalletPay.IDLE) {
            status.setText(label);
            status.setVisibility(View.VISIBLE);
        } else if (!connected) {
            status.setVisibility(View.GONE);
        }
    }

    /**
     * Renders the outcome of a wallet payment attempt. Note what this does NOT
     * do: complete the purchase. The settlement poll owns that transition, so a
     * SENT here only reports what the wallet said and leaves the screen waiting
     * exactly as a QR payment does.
     */
    private void renderWalletPay(BuyCreditViewModel.WalletPay walletPay) {
        View root = getView();
        if (root == null) {
            return;
        }
        MaterialButton pay = root.findViewById(R.id.buy_ln_wallet_pay);
        TextView status = root.findViewById(R.id.buy_ln_wallet_status);
        if (pay.getVisibility() != View.VISIBLE) {
            return; // no wallet connected; nothing to report
        }
        switch (walletPay) {
            case PAYING -> {
                pay.setEnabled(false);
                status.setText(R.string.buy_credit_wallet_paying);
                status.setVisibility(View.VISIBLE);
            }
            case SENT -> {
                // Stays disabled: the invoice is paid, and re-enabling a "Pay"
                // button under a paid invoice is an invitation to pay twice.
                pay.setEnabled(false);
                status.setText(R.string.buy_credit_wallet_sent);
                status.setVisibility(View.VISIBLE);
            }
            case FAILED -> {
                pay.setEnabled(true);
                String reason = mViewModel.getWalletPayError();
                status.setText(reason != null ? reason
                        : getString(R.string.buy_credit_wallet_unconfirmed));
                status.setVisibility(View.VISIBLE);
            }
            default -> {
                pay.setEnabled(true);
                bindWalletControls();
            }
        }
    }

    /**
     * The connect/manage dialog: paste a connection string, scan it off the
     * wallet's own QR, or disconnect.
     *
     * <p>Connecting VERIFIES before it stores — {@code get_info} over the real
     * relay, which is the only thing that proves the relay is reachable, the
     * keys agree, and the connection is permitted to pay. A parse alone would
     * happily accept a revoked or read-only connection and defer the failure to
     * the moment money is being spent.
     */
    private void showWalletDialog() {
        NwcWallet wallet = new NwcWallet(requireContext());
        String existing = wallet.label();

        EditText input = new EditText(requireContext());
        input.setHint(R.string.buy_credit_wallet_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = getResources().getDimensionPixelSize(R.dimen.dialog_padding_standard);
        FrameLayout wrapper = new FrameLayout(requireContext());
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.buy_credit_wallet_dialog_title)
                .setMessage(existing != null
                        ? getString(R.string.buy_credit_wallet_dialog_connected, existing)
                        : getString(R.string.buy_credit_wallet_dialog_body))
                .setView(wrapper)
                .setNeutralButton(R.string.buy_credit_wallet_scan,
                        (dialog, which) -> openWalletScanner())
                .setPositiveButton(R.string.buy_credit_wallet_connect_action, (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        connectWallet(text);
                    }
                });
        if (existing != null) {
            // "Disconnect", not "revoke": this forgets the string on THIS
            // device and nothing else — the connection stays live in the
            // wallet until the user removes it there.
            builder.setNegativeButton(R.string.buy_credit_wallet_disconnect, (dialog, which) -> {
                wallet.disconnect();
                bindWalletControls();
                snackbar(getString(R.string.buy_credit_wallet_disconnected));
            });
        } else {
            builder.setNegativeButton(android.R.string.cancel, null);
        }
        builder.show();
    }

    private void openWalletScanner() {
        Bundle args = new Bundle();
        args.putInt(P2pScanFragment.ARG_TITLE_RES, R.string.buy_credit_wallet_scan_title);
        mNavController.navigate(R.id.action_buy_to_scan, args);
    }

    /**
     * A scanned payload lands back on the connect dialog rather than connecting
     * straight through — a QR is a bearer secret pointed at a camera, and the
     * review step costs nothing because the dialog already exists. A payload
     * that isn't an NWC string is reported and DROPPED (it is not a typo to
     * correct).
     */
    private void observeWalletScanResult() {
        NavBackStackEntry entry = mNavController.getCurrentBackStackEntry();
        if (entry == null) {
            return;
        }
        entry.getSavedStateHandle()
                .getLiveData(P2pScanFragment.RESULT_CODE, (String) null)
                .observe(getViewLifecycleOwner(), code -> {
                    if (code == null) {
                        return;
                    }
                    // set(key, null), NEVER remove(key): remove() detaches the
                    // handle's cached LiveData and the SECOND scan of a session
                    // would silently never arrive.
                    entry.getSavedStateHandle().set(P2pScanFragment.RESULT_CODE, (String) null);
                    String trimmed = code.trim();
                    if (!NwcUri.looksLikeNwcUri(trimmed)) {
                        snackbar(getString(R.string.buy_credit_wallet_scan_bad));
                        return;
                    }
                    connectWallet(trimmed);
                });
    }

    /** Parses, PROVES (get_info over the relay), then stores. */
    private void connectWallet(String connectionString) {
        final NwcUri parsed;
        try {
            parsed = NwcUri.parse(connectionString);
        } catch (NwcUri.MalformedException e) {
            snackbar(getString(R.string.buy_credit_wallet_bad, e.getMessage()));
            return;
        }
        snackbar(getString(R.string.buy_credit_wallet_connecting));
        mConnectExecutor.execute(() -> {
            String error = null;
            try {
                JSONObject info = new NwcClient(parsed, mHttp).getInfo();
                if (!NwcClient.supportsPayInvoice(info)) {
                    // A read-only connection parses perfectly and then fails at
                    // the worst possible moment. Catch it at connect time.
                    error = getString(R.string.buy_credit_wallet_readonly);
                }
            } catch (IOException | RuntimeException e) {
                error = getString(R.string.buy_credit_wallet_unreachable);
            }
            final String finalError = error;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                if (finalError != null) {
                    snackbar(finalError);
                    return;
                }
                new NwcWallet(requireContext()).store(connectionString);
                mViewModel.clearWalletPay();
                bindWalletControls();
                snackbar(getString(R.string.buy_credit_wallet_connected, parsed.displayLabel()));
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // The connect probe is a bounded relay round trip, so shutdown() (not
        // shutdownNow) lets an in-flight one finish and drop its result on the
        // isAdded() guard, rather than interrupting a socket mid-handshake.
        mConnectExecutor.shutdown();
    }

    private void openInWallet() {
        if (mPayRequest == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("lightning:" + mPayRequest))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            snackbar(getString(R.string.buy_credit_no_wallet));
        }
    }

    // ---- stripe (hosted Checkout, embedded) ----

    /** Pre-warms the embedded Checkout (WebView creation + Stripe preconnects)
     *  so tapping Continue only pays for the quote + the page itself. Idempotent;
     *  called when the Card rail is selected. */
    private void warmStripeWebView() {
        WebView web = ensureStripeWebView();
        if (web == null || mStripeWebWarmed) {
            return;
        }
        mStripeWebWarmed = true;
        web.loadDataWithBaseURL(null, STRIPE_WARMUP_HTML, "text/html", "utf-8", null);
    }

    private void bindStripe(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(true);
        mCheckoutUrl = s.checkoutUrl;
        ((TextView) requireView().findViewById(R.id.buy_stripe_amount)).setText(payAmountText(s));
        if (mCheckoutOpened || mCheckoutUrl == null) {
            return;
        }
        mCheckoutOpened = true;
        // Checkout stays IN the flow: the hosted page loads in an embedded
        // WebView, so paying never leaves this screen (the old flow bounced to a
        // browser tab and stranded the user there while this screen polled
        // underneath). The success/cancel redirects are intercepted below; the
        // ViewModel's poll is what actually completes the purchase either way.
        WebView web = ensureStripeWebView();
        if (web != null) {
            web.setVisibility(View.VISIBLE);
            // Spinner until Checkout's first real paint (hidden by
            // onPageCommitVisible below) — the page is a heavy JS app and an
            // empty container reads as a hang.
            if (mStripeWebProgress != null) {
                mStripeWebProgress.setVisibility(View.VISIBLE);
            }
            web.loadUrl(mCheckoutUrl);
            return;
        }
        // No WebView on this device — the old browser-tab flow, with its
        // explanatory copy.
        View hint = requireView().findViewById(R.id.buy_stripe_hint);
        if (hint != null) {
            hint.setVisibility(View.VISIBLE);
        }
        openCheckout();
    }

    /** Lazily creates + configures the embedded Checkout WebView. Returns null
     *  (permanently, {@link #mStripeWebFailed}) when the platform can't provide
     *  one — the caller falls back to the browser tab. */
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private WebView ensureStripeWebView() {
        if (mStripeWeb != null) {
            return mStripeWeb;
        }
        if (mStripeWebFailed) {
            return null;
        }
        View root = getView();
        if (root == null) {
            return null;
        }
        FrameLayout container = root.findViewById(R.id.buy_stripe_web_container);
        if (container == null) {
            return null;
        }
        WebView web;
        try {
            web = new WebView(requireContext());
        } catch (RuntimeException e) {
            // Missing/updating WebView provider — possible on de-Googled devices.
            mStripeWebFailed = true;
            return null;
        }
        // Hosted Checkout is a JS app and keeps state in DOM storage.
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // Transparent until the page paints — the default opaque white flashed
        // hard against the dark theme while Checkout loaded.
        web.setBackgroundColor(0);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleCheckoutNavigation(request.getUrl());
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                // First real paint of the checkout — drop the spinner. Also
                // fires for the warm-up page, where the overlay is already gone.
                if (mStripeWebProgress != null) {
                    mStripeWebProgress.setVisibility(View.GONE);
                }
            }
        });
        // The whole wizard scrolls in a ScrollView; hand vertical drags over the
        // Checkout to the WebView or the outer ScrollView steals them and the
        // payment form can't scroll.
        web.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ProgressBar progress = new ProgressBar(requireContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        progress.setVisibility(View.GONE);
        container.addView(progress, lp);
        mStripeWebProgress = progress;
        mStripeWeb = web;
        return web;
    }

    /** Routes the embedded Checkout's navigations: the mint's success/cancel
     *  redirect URLs end the embed (the poll owns actual completion — success is
     *  confirmed by {@code /v1/mint/issue}, never by reaching a URL); an
     *  app-scheme URL (a bank's 3-D Secure app) goes to the system; everything
     *  else (stripe.com, 3DS web challenges) stays in the WebView. */
    private boolean handleCheckoutNavigation(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            // A payment/bank app deeplink out of 3-D Secure — let the system
            // handle it; the Checkout page continues when the user returns.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                // No handler — swallow; staying on the page beats crashing out.
            }
            return true;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        boolean isRedirectTarget = host != null && path != null
                && (host.equals("firedown.app") || host.endsWith(".firedown.app"))
                && path.startsWith("/pay/");
        if (!isRedirectTarget) {
            return false;
        }
        if (path.contains("cancel")) {
            // The user backed out on Stripe's page — return to the picker (also
            // stops the poll via the flow-generation bump).
            mViewModel.backToPick();
        } else {
            // Payment submitted — drop the embed and let the waiting strip show;
            // the poll flips the wizard to SUCCESS the moment the mint settles.
            hideStripeWeb();
        }
        return true;
    }

    /** Collapses the embedded Checkout (payment submitted / step left). */
    private void hideStripeWeb() {
        if (mStripeWeb != null) {
            mStripeWeb.loadUrl("about:blank");
            mStripeWeb.setVisibility(View.GONE);
        }
    }

    /** The browser-tab escape (the strip's open-in-browser button, and the whole
     *  flow when no WebView exists). The poll lives in the ViewModel, so paying
     *  in a tab still completes this screen. */
    private void openCheckout() {
        if (mCheckoutUrl == null) {
            return;
        }
        Uri uri = Uri.parse(mCheckoutUrl);
        // Open the hosted Checkout IN Firedown (it IS a browser) rather than handing
        // the user off to whatever the OS default browser is — leaving the app is bad
        // UX for a browser. setPackage pins the ACTION_VIEW to our own app; NEW_TASK
        // opens it as a separate task so THIS settings screen stays alive and keeps
        // polling for the payment (deliberately NOT the Settings→browser
        // result-handshake, which finishes the activity and would kill the poll).
        Intent inApp = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(requireContext().getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(inApp);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Our own browser activity didn't resolve for this URL — fall through
            // to the system default so the user can still complete payment.
        }
        Intent external = new Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(external);
        } catch (ActivityNotFoundException e) {
            snackbar(getString(R.string.buy_credit_no_browser));
        }
    }

    // ---- success ----

    private void bindSuccess(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(false);
        String title = s.sizeGb > 0 && s.durationMonths > 0
                ? getString(R.string.buy_credit_success_title_plan, s.sizeGb, formatDuration(s.durationMonths))
                : getString(R.string.buy_credit_success_title, s.redeemedGbMonths);
        ((TextView) requireView().findViewById(R.id.buy_success_title)).setText(title);
        // No GB-months here: the title above already states what was bought
        // ("Up to X GB for 1 year"); the wallet speaks TIME on the Cloud
        // screen's timeline, and restating the ledger balance in its internal
        // unit was one of the recurring "GB-months is confusing" reports.
        ((TextView) requireView().findViewById(R.id.buy_success_balance))
                .setText(R.string.buy_credit_success_added);
        // No recovery-code card on success anymore: the account (and its saved key)
        // always exists BEFORE this flow — the Cloud hub gates buying on a key, and
        // creating one there forces the "I've saved it" step. So there's never a
        // freshly-minted, unsaved code to surface here.
    }

    // ---- error ----

    private void bindError(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(false);
        ((TextView) requireView().findViewById(R.id.buy_error_text)).setText(s.errorMessage);
    }

    // ---- helpers ----

    /** Toggles the "Back returns to the picker" behaviour for the pay screens. */
    private void setPayBackEnabled(boolean enabled) {
        if (mPayBack != null) {
            mPayBack.setEnabled(enabled);
        }
    }

    private void copyToClipboard(String label, String text, String toast) {
        if (text == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            snackbar(toast);
        }
    }

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }

    /** The pay-screen headline: "$30 · up to 50 GB for 1 year" (plan) or
     *  "$18 · 500 GB-months" (legacy). */
    private String payAmountText(BuyCreditViewModel.UiState s) {
        if (s.sizeGb > 0 && s.durationMonths > 0) {
            return getString(R.string.buy_credit_pay_amount_plan,
                    formatUsd(s.amountCents), s.sizeGb, formatDuration(s.durationMonths));
        }
        return getString(R.string.buy_credit_pay_amount, formatUsd(s.amountCents), s.denomGbMonths);
    }

    /** Localized coverage: whole years ("1 year" / "2 years") when a multiple of
     *  12, else months ("3 months") — via plurals across every locale. */
    private String formatDuration(int months) {
        if (months > 0 && months % 12 == 0) {
            int years = months / 12;
            return getResources().getQuantityString(R.plurals.buy_credit_years, years, years);
        }
        return getResources().getQuantityString(R.plurals.buy_credit_months, months, months);
    }

    /** cents → "$5" / "$18.50" (2 decimals only when not a whole dollar). */
    private static String formatUsd(long cents) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.US);
        if (cents % 100 == 0) {
            nf.setMaximumFractionDigits(0);
        }
        return nf.format(cents / 100.0);
    }

    /** Per-unit price so tiers are comparable, e.g. "3.6¢" (US cents per
     *  GB-month). USD throughout — the mint prices in USD cents. */
    private static String formatPerGbMonth(BuyCreditViewModel.Option opt) {
        double centsPer = opt.denomGbMonths > 0 ? (double) opt.priceCents / opt.denomGbMonths : 0;
        return String.format(Locale.US, "%.1f¢", centsPer);
    }
}
