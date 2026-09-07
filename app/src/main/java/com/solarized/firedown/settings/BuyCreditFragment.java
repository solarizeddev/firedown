package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.EditText;
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
 * one {@link BuyCreditViewModel}: pick denomination + rail → pay (Lightning
 * invoice QR / on-chain Bitcoin address QR) → success. Both rails poll the same
 * {@code /v1/mint/issue}; the only difference is what the pay stage shows and
 * how long it waits. (Cards were a third rail and were removed with the
 * server's Stripe integration — see BuyCreditViewModel.RAIL_ONCHAIN.)
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
    private View mStepOnchain;
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

    // Lightning pay state: the BOLT11 (open-in-wallet / copy).
    private String mPayRequest;
    // On-chain pay state: the BIP21 URI (open-in-wallet) + bare address (copy).
    private String mBtcUri;
    private String mBtcAddress;
    /** The phase the pay-screen Back callback is serving, so it can branch:
     *  Lightning → straight back to the picker; on-chain → the cancel
     *  confirmation (an on-chain record is never dropped silently). */
    private BuyCreditViewModel.Phase mPayPhase;

    /** Intercepts Back on a pay screen (Lightning → back to the picker, which
     *  stops polling; on-chain → the cancel confirmation); enabled only while a
     *  pay screen is shown. */
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
        mStepOnchain = view.findViewById(R.id.buy_step_onchain);
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
            mSelectedRail = checkedId == R.id.buy_rail_bitcoin
                    ? BuyCreditViewModel.RAIL_ONCHAIN : BuyCreditViewModel.RAIL_LIGHTNING;
        });

        mContinue.setOnClickListener(v -> {
            if (mSelectedOption != null) {
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

        // On-chain pay actions.
        view.findViewById(R.id.buy_btc_open_wallet).setOnClickListener(v -> openInBitcoinWallet());
        view.findViewById(R.id.buy_btc_copy).setOnClickListener(v -> copyToClipboard(
                getString(R.string.buy_credit_btc_address_label), mBtcAddress,
                getString(R.string.buy_credit_btc_copied)));
        view.findViewById(R.id.buy_btc_cancel).setOnClickListener(v -> confirmCancelOnchain());

        // Success actions.
        view.findViewById(R.id.buy_done).setOnClickListener(v -> mNavController.popBackStack());
        view.findViewById(R.id.buy_backup_more).setOnClickListener(v -> mNavController.popBackStack());

        // Error retry.
        view.findViewById(R.id.buy_error_retry).setOnClickListener(v -> mViewModel.retry());

        // A pay screen's Back returns to the picker (and stops polling) instead of
        // leaving the wizard; elsewhere Back leaves normally (disabled by default,
        // enabled only while a pay screen is shown). On the on-chain screen Back
        // is the cancel confirmation instead: the address may already have been
        // paid from another wallet, so there is no silent way off it.
        mPayBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (mPayPhase == BuyCreditViewModel.Phase.PAY_ONCHAIN) {
                    confirmCancelOnchain();
                } else {
                    mViewModel.backToPick();
                }
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

    private void render(BuyCreditViewModel.UiState s) {
        showStep(s.phase);
        switch (s.phase) {
            case PICK -> bindPick(s);
            case PAY_LIGHTNING -> bindLightning(s);
            case PAY_ONCHAIN -> bindOnchain(s);
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
        mStepOnchain.setVisibility(phase == BuyCreditViewModel.Phase.PAY_ONCHAIN ? View.VISIBLE : View.GONE);
        mStepSuccess.setVisibility(phase == BuyCreditViewModel.Phase.SUCCESS ? View.VISIBLE : View.GONE);
        mStepError.setVisibility(phase == BuyCreditViewModel.Phase.ERROR ? View.VISIBLE : View.GONE);
        mPayPhase = phase;
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

    // ---- on-chain Bitcoin ----

    /**
     * The on-chain pay stage: the BIP21 URI as a QR (what a wallet's camera
     * reads — amount included, so the wallet pre-fills it), the bare address for
     * copying, the exact BTC amount stated in words, and a status line that
     * flips from "waiting" to "detected, confirming" once the mint has seen the
     * transaction. Nothing here completes the purchase — the ViewModel's poll
     * does, exactly as for Lightning — and unlike Lightning the wait is minutes
     * to hours, so the hint says the user may leave and come back.
     */
    private void bindOnchain(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(true);
        mBtcUri = s.payRequest;
        mBtcAddress = s.address;
        View root = requireView();
        ((TextView) root.findViewById(R.id.buy_btc_amount)).setText(payAmountText(s));
        ((TextView) root.findViewById(R.id.buy_btc_send))
                .setText(getString(R.string.buy_credit_btc_send, formatBtc(s.amountSats)));
        ((TextView) root.findViewById(R.id.buy_btc_address)).setText(s.address);
        ((TextView) root.findViewById(R.id.buy_btc_status)).setText(s.paymentDetected
                ? R.string.buy_credit_btc_detected
                : R.string.buy_credit_waiting);
        ImageView qr = root.findViewById(R.id.buy_btc_qr);
        if (s.payRequest != null) {
            // Encoded VERBATIM (byte mode), not uppercased like the BOLT11: a
            // bech32 address is case-insensitive, but BIP21's query keys
            // ("amount=") are not, and an uppercased key silently drops the
            // pre-filled amount in wallets that parse them strictly.
            Bitmap bmp = QrCodes.encode(s.payRequest);
            if (bmp != null) {
                qr.setImageBitmap(bmp);
            }
        }
    }

    /** Hands the BIP21 URI to whatever wallet claims the {@code bitcoin:}
     *  scheme (amount pre-filled from the URI). */
    private void openInBitcoinWallet() {
        if (mBtcUri == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mBtcUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            snackbar(getString(R.string.buy_credit_no_btc_wallet));
        }
    }

    /**
     * The only door off the on-chain stage that forgets the payment. It asks
     * first, and the copy states the consequence rather than the mechanism:
     * bitcoin already sent to this address can't be matched to a credit
     * afterwards — the mint never learns a return address, so there is no
     * refund on this rail. "Keep waiting" is the safe default (cancel is the
     * negative button, not the primary).
     */
    private void confirmCancelOnchain() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.buy_credit_btc_cancel_title)
                .setMessage(R.string.buy_credit_btc_cancel_body)
                .setPositiveButton(R.string.buy_credit_btc_keep_waiting, null)
                .setNegativeButton(R.string.buy_credit_btc_cancel_confirm,
                        (dialog, which) -> mViewModel.cancelPendingPurchase())
                .show();
    }

    /** sats → a decimal BTC string with trailing zeros trimmed ("0.00017",
     *  "1.5"), integer arithmetic only — the same rendering the mint puts in
     *  the BIP21 URI, so the words and the QR never disagree. */
    private static String formatBtc(long sats) {
        long whole = sats / 100_000_000L;
        long frac = sats % 100_000_000L;
        String text = String.format(Locale.ROOT, "%d.%08d", whole, frac);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
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
