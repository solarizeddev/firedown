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
import androidx.core.content.ContextCompat;
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
    private int mSelectedDenom = -1;
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
            if (mSelectedDenom > 0) {
                mCheckoutOpened = false;
                mViewModel.startPurchase(mSelectedDenom, mSelectedRail);
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
        // Rebuild the denomination cards from the server's active keysets.
        mDenomContainer.removeAllViews();
        mSelectedDenom = -1;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (BuyCreditViewModel.Option opt : s.options) {
            MaterialCardView card = (MaterialCardView) inflater.inflate(
                    R.layout.item_buy_credit_denom, mDenomContainer, false);
            TextView amt = card.findViewById(R.id.buy_denom_amt);
            TextView price = card.findViewById(R.id.buy_denom_price);
            amt.setText(getString(R.string.buy_credit_denom_amount, opt.denomGbMonths));
            price.setText(formatUsd(opt.priceCents));
            card.setTag(opt);
            card.setOnClickListener(v -> selectDenom(card, opt));
            mDenomContainer.addView(card);
        }
        // Default to the middle option (the "most picked" tier in the sketch), else
        // the only/first one.
        int defaultIndex = s.options.size() >= 3 ? 1 : 0;
        if (mDenomContainer.getChildCount() > defaultIndex) {
            MaterialCardView def = (MaterialCardView) mDenomContainer.getChildAt(defaultIndex);
            selectDenom(def, (BuyCreditViewModel.Option) def.getTag());
        }
    }

    private void selectDenom(MaterialCardView selected, BuyCreditViewModel.Option opt) {
        mSelectedDenom = opt.denomGbMonths;
        int brand = ContextCompat.getColor(requireContext(), R.color.brand_orange);
        int outline = MaterialColors.getColor(selected, com.google.android.material.R.attr.colorOutlineVariant);
        int stroke = Math.round(getResources().getDisplayMetrics().density);
        for (int i = 0; i < mDenomContainer.getChildCount(); i++) {
            MaterialCardView card = (MaterialCardView) mDenomContainer.getChildAt(i);
            boolean on = card == selected;
            card.setStrokeColor(on ? brand : outline);
            card.setStrokeWidth(on ? stroke * 2 : stroke);
        }
        mContinue.setText(getString(R.string.buy_credit_continue, formatUsd(opt.priceCents)));
        mContinue.setEnabled(true);
    }

    // ---- lightning ----

    private void bindLightning(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(true);
        mPayRequest = s.payRequest;
        ((TextView) requireView().findViewById(R.id.buy_ln_amount))
                .setText(getString(R.string.buy_credit_pay_amount, formatUsd(s.amountCents), s.denomGbMonths));
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
        ((TextView) requireView().findViewById(R.id.buy_stripe_amount))
                .setText(getString(R.string.buy_credit_pay_amount, formatUsd(s.amountCents), s.denomGbMonths));
        if (!mCheckoutOpened && mCheckoutUrl != null) {
            mCheckoutOpened = true;
            openCheckout();
        }
    }

    private void openCheckout() {
        if (mCheckoutUrl == null) {
            return;
        }
        // Open the mint's hosted Checkout in a browser tab. Deliberately NOT the
        // Settings→browser result-handshake (which would finish this activity and
        // kill the poll loop) — a plain ACTION_VIEW keeps this screen alive polling
        // while the user pays in the browser, then returns.
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mCheckoutUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            snackbar(getString(R.string.buy_credit_no_browser));
        }
    }

    // ---- success ----

    private void bindSuccess(BuyCreditViewModel.UiState s) {
        setPayBackEnabled(false);
        ((TextView) requireView().findViewById(R.id.buy_success_title))
                .setText(getString(R.string.buy_credit_success_title, s.redeemedGbMonths));
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

    /** cents → "$5" / "$18.50" (2 decimals only when not a whole dollar). */
    private static String formatUsd(long cents) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.US);
        if (cents % 100 == 0) {
            nf.setMaximumFractionDigits(0);
        }
        return nf.format(cents / 100.0);
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
