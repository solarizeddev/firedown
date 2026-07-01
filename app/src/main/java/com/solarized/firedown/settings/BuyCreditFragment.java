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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.BuyCreditViewModel;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The "Add storage credit" purchase wizard — a single full-page nav destination
 * (like {@link com.solarized.firedown.phone.fragments.LanShareFragment}, whose
 * QR/PIN handover also wants a whole page) that switches between step views under
 * one {@link BuyCreditViewModel}: pick denomination + rail → pay (Lightning QR /
 * Stripe hosted Checkout in a tab) → success. No Stripe SDK — the card path opens
 * the mint's hosted Checkout URL in a browser tab and polls the same
 * {@code /v1/mint/issue} as Lightning.
 */
@AndroidEntryPoint
public class BuyCreditFragment extends Fragment {

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
    private TextView mDurationSave;
    private TextView mSizeLabel;
    private View mGbmExplainer;
    private View mSoftcapNote;
    private View mOneOffNote;
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
    /** So entering the Stripe step doesn't re-launch the browser on every re-render
     *  (config change, poll tick). */
    private boolean mCheckoutOpened;

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
        mDurationSave = view.findViewById(R.id.buy_duration_save);
        mSizeLabel = view.findViewById(R.id.buy_size_label);
        mGbmExplainer = view.findViewById(R.id.buy_gbm_explainer);
        mSoftcapNote = view.findViewById(R.id.buy_softcap_note);
        mOneOffNote = view.findViewById(R.id.buy_oneoff_note);

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
        });

        mContinue.setOnClickListener(v -> {
            if (mSelectedOption != null) {
                mCheckoutOpened = false;
                mViewModel.startPurchase(mSelectedOption, mSelectedRail);
            }
        });

        // Lightning pay actions.
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
        mViewModel.loadOptions();
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
    }

    // ---- pick ----

    private void bindPick(BuyCreditViewModel.UiState s) {
        mSelectedOption = null;
        mContinue.setEnabled(false);
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
        // for sale — the tiles still say "for <duration>").
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        mDurationToggle.removeAllViews();
        List<Integer> buttonIds = new ArrayList<>();
        for (int months : durations) {
            MaterialButton btn = (MaterialButton) inflater.inflate(
                    R.layout.item_buy_duration_button, mDurationToggle, false);
            int id = View.generateViewId();
            btn.setId(id);
            btn.setTag(months);
            btn.setText(formatDuration(months));
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
            card.setTag(opt);
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
        updateSaveNudge(durationMonths);
    }

    /** Contextual bulk-discount nudge: shows the saving of the cheapest-per-
     *  GB-month LONGER plan than the one currently selected, and hides entirely
     *  once the longest (best-value) duration is selected — so it never tells the
     *  user to "pick a longer plan" when there isn't one. Data-driven, no
     *  hardcoded percent. */
    private void updateSaveNudge(int selectedMonths) {
        mDurationSave.setVisibility(View.GONE);
        double selectedRate = bestRateForDuration(selectedMonths);
        if (selectedRate <= 0) {
            return;
        }
        double bestLongerRate = -1;
        for (BuyCreditViewModel.Option o : mPlanOptions) {
            if (o.durationMonths > selectedMonths && o.denomGbMonths > 0) {
                double rate = (double) o.priceCents / o.denomGbMonths;
                if (bestLongerRate < 0 || rate < bestLongerRate) {
                    bestLongerRate = rate;
                }
            }
        }
        if (bestLongerRate <= 0 || bestLongerRate >= selectedRate) {
            return; // no longer plan, or it isn't actually cheaper
        }
        int pct = (int) Math.round((1.0 - bestLongerRate / selectedRate) * 100.0);
        if (pct <= 0) {
            return;
        }
        mDurationSave.setText(getString(R.string.buy_credit_save_nudge, pct));
        mDurationSave.setVisibility(View.VISIBLE);
    }

    /** The lowest price-per-GB-month across the tiles of a given duration. */
    private double bestRateForDuration(int durationMonths) {
        double best = -1;
        for (BuyCreditViewModel.Option o : mPlanOptions) {
            if (o.durationMonths == durationMonths && o.denomGbMonths > 0) {
                double rate = (double) o.priceCents / o.denomGbMonths;
                if (best < 0 || rate < best) {
                    best = rate;
                }
            }
        }
        return best;
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
            card.setOnClickListener(v -> selectCard(card, opt));
            mDenomContainer.addView(card);
        }
        int defaultIndex = options.size() >= 3 ? 1 : 0;
        if (mDenomContainer.getChildCount() > defaultIndex) {
            MaterialCardView def = (MaterialCardView) mDenomContainer.getChildAt(defaultIndex);
            selectCard(def, (BuyCreditViewModel.Option) def.getTag());
        }
    }

    /** Highlights the chosen card (brand 2dp stroke) and enables Continue. Shared
     *  by the plan tiles and the legacy denomination cards. */
    private void selectCard(MaterialCardView selected, BuyCreditViewModel.Option opt) {
        mSelectedOption = opt;
        if (opt.isPlan()) {
            mPreferredSizeGb = opt.sizeGb;
        }
        // Selection is a strong NEUTRAL outline, not coral — the only coral on
        // this screen is the Continue button. onSurface reads clearly in both
        // themes (near-white in dark, near-black in light).
        int selectedColor = MaterialColors.getColor(selected, com.google.android.material.R.attr.colorOnSurface);
        int outline = MaterialColors.getColor(selected, com.google.android.material.R.attr.colorOutlineVariant);
        int stroke = Math.round(getResources().getDisplayMetrics().density);
        for (int i = 0; i < mDenomContainer.getChildCount(); i++) {
            MaterialCardView card = (MaterialCardView) mDenomContainer.getChildAt(i);
            boolean on = card == selected;
            card.setStrokeColor(on ? selectedColor : outline);
            card.setStrokeWidth(on ? stroke * 2 : stroke);
        }
        mContinue.setText(getString(R.string.buy_credit_continue, formatUsd(opt.priceCents)));
        mContinue.setEnabled(true);
    }

    // ---- lightning ----

    private void bindLightning(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(true);
        mPayRequest = s.payRequest;
        ((TextView) requireView().findViewById(R.id.buy_ln_amount)).setText(payAmountText(s));
        TextView invoice = requireView().findViewById(R.id.buy_ln_invoice);
        invoice.setText(s.payRequest);
        ImageView qr = requireView().findViewById(R.id.buy_ln_qr);
        if (s.payRequest != null) {
            // Uppercase for the QR alphanumeric mode (a BOLT11 is case-insensitive).
            Bitmap bmp = encodeQr("lightning:" + s.payRequest.toUpperCase(Locale.ROOT));
            if (bmp != null) {
                qr.setImageBitmap(bmp);
            }
        }
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

    // ---- stripe (hosted Checkout) ----

    private void bindStripe(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(true);
        mCheckoutUrl = s.checkoutUrl;
        ((TextView) requireView().findViewById(R.id.buy_stripe_amount)).setText(payAmountText(s));
        if (!mCheckoutOpened && mCheckoutUrl != null) {
            mCheckoutOpened = true;
            openCheckout();
        }
    }

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
        ((TextView) requireView().findViewById(R.id.buy_success_balance))
                .setText(getString(R.string.buy_credit_success_balance, formatGbMonths(s.balanceGbMonths)));

        // If a brand-new account was minted for this purchase, surface its recovery
        // code so the user can save it (it's the only key to their backups).
        View codeCard = requireView().findViewById(R.id.buy_success_code_card);
        if (s.mintedRecoveryCode != null) {
            codeCard.setVisibility(View.VISIBLE);
            TextView code = requireView().findViewById(R.id.buy_success_code);
            code.setText(s.mintedRecoveryCode);
            requireView().findViewById(R.id.buy_success_copy_code).setOnClickListener(v ->
                    copyToClipboard(getString(R.string.buy_credit_recovery_code_title),
                            s.mintedRecoveryCode, getString(R.string.buy_credit_code_copied)));
        } else {
            codeCard.setVisibility(View.GONE);
        }
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

    /** A GB-months balance, trimming a trailing ".0" (100.0 → "100"). */
    private static String formatGbMonths(double v) {
        if (v == Math.rint(v)) {
            return Long.toString(Math.round(v));
        }
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    @Nullable
    private static Bitmap encodeQr(@NonNull String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
