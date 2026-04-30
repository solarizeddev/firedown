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
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.solarized.firedown.R;
import com.solarized.firedown.donate.BitcoinAddressProvider;
import com.solarized.firedown.donate.LightningInvoiceFetcher;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Native value-for-value donate screen.
 *
 * <p>Three collapsed cards — Lightning, Bitcoin on-chain, and fiat (via
 * Ko-fi) — let the user pick the rail that fits their situation. Only
 * Lightning and Bitcoin have expandable bodies; the fiat row hands off
 * directly to Ko-fi in the system browser.</p>
 *
 * <p>Only one card is open at a time; tapping a different header
 * collapses the previously open one. This keeps the screen short
 * enough that all three rails are visible above the fold on most
 * devices.</p>
 */
@AndroidEntryPoint
public class DonateFragment extends BasePreferenceFragment {

    private static final String TAG = DonateFragment.class.getName();

    /** Lightning Address — same one used in donate.html. */
    private static final String LIGHTNING_ADDRESS = "solarized@getalby.com";

    /** Ko-fi page handing off to Stripe / PayPal. */
    private static final String KOFI_URL = "https://ko-fi.com/solarized";

    private static final int DEFAULT_SATS = 5000;

    private LightningInvoiceFetcher mInvoiceFetcher;
    private BitcoinAddressProvider  mBtcProvider;

    private int mSelectedSats = DEFAULT_SATS;

    /** Address shown in this visit; pinned so background fetches don't change it under the user. */
    private String mDisplayedBtcAddress;

    // Card containers (used to enforce single-open behavior)
    private View mLightningHeader, mLightningBody, mLightningChevron;
    private View mBitcoinHeader,   mBitcoinBody,   mBitcoinChevron;

    // Lightning body
    private MaterialButtonToggleGroup mAmountGroup;
    private EditText                  mCustomSatsInput;
    private MaterialButton            mPayButton;
    private TextView                  mStatusText;
    private LinearLayout              mInvoiceFallbackWrap;
    private ImageView                 mInvoiceQrImage;
    private MaterialButton            mCopyInvoiceButton;
    private MaterialButton            mOpenWalletButton;

    // Bitcoin body
    private ImageView                 mBtcQrImage;
    private TextView                  mBtcAddressText;

    @Inject
    OkHttpClient okHttpClient;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInvoiceFetcher = new LightningInvoiceFetcher(okHttpClient);
        mBtcProvider    = new BitcoinAddressProvider(mActivity, okHttpClient);
        // Kick off the first-launch fetch in the background. If this is
        // a repeat visit, this is a no-op.
        mBtcProvider.fetchIfNeeded();
    }


    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_donate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ── Card containers ─────────────────────────────────────────
        mLightningHeader   = view.findViewById(R.id.donate_lightning_header);
        mLightningBody     = view.findViewById(R.id.donate_lightning_body);
        mLightningChevron  = view.findViewById(R.id.donate_lightning_chevron);

        mBitcoinHeader     = view.findViewById(R.id.donate_bitcoin_header);
        mBitcoinBody       = view.findViewById(R.id.donate_bitcoin_body);
        mBitcoinChevron    = view.findViewById(R.id.donate_bitcoin_chevron);


        // ── Lightning body views ────────────────────────────────────
        mAmountGroup         = view.findViewById(R.id.donate_amount_group);
        mCustomSatsInput     = view.findViewById(R.id.donate_custom_sats);
        mPayButton           = view.findViewById(R.id.donate_pay_btn);
        mStatusText          = view.findViewById(R.id.donate_status);
        mInvoiceFallbackWrap = view.findViewById(R.id.donate_invoice_fallback);
        mInvoiceQrImage      = view.findViewById(R.id.donate_invoice_qr);
        mCopyInvoiceButton   = view.findViewById(R.id.donate_copy_invoice_btn);
        mOpenWalletButton    = view.findViewById(R.id.donate_open_wallet_btn);

        // ── Bitcoin body views ──────────────────────────────────────
        mBtcQrImage          = view.findViewById(R.id.btc_qr);
        mBtcAddressText      = view.findViewById(R.id.btc_address);
        MaterialButton btcCopyBtn   = view.findViewById(R.id.btc_copy_btn);
        MaterialButton btcWalletBtn = view.findViewById(R.id.btc_open_wallet);

        setupCardHeaders();
        setupLightningSection();
        setupBitcoinSection(btcCopyBtn, btcWalletBtn);
    }

    // ─────────────────────────────────────────────────────────────────
    // Expand/collapse coordination
    // ─────────────────────────────────────────────────────────────────

    private void setupCardHeaders() {
        mLightningHeader.setOnClickListener(v -> toggleCard(0));
        mBitcoinHeader  .setOnClickListener(v -> toggleCard(1));
        // Fiat header is a direct handoff, no toggle. Wired in setupFiatSection.
    }

    /**
     * Open the given card (0 = Lightning, 1 = Bitcoin) and close the others.
     * If the requested card is already open, collapses everything.
     */
    private void toggleCard(int which) {
        boolean openLightning = which == 0 && mLightningBody.getVisibility() != View.VISIBLE;
        boolean openBitcoin   = which == 1 && mBitcoinBody.getVisibility()   != View.VISIBLE;

        setCardOpen(mLightningBody, mLightningChevron, openLightning);
        setCardOpen(mBitcoinBody,   mBitcoinChevron,   openBitcoin);
    }

    private void setCardOpen(@NonNull View body, @NonNull View chevron, boolean open) {
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        chevron.setRotation(open ? 180f : 0f);
    }

    // ─────────────────────────────────────────────────────────────────
    // Lightning
    // ─────────────────────────────────────────────────────────────────

    private void setupLightningSection() {
        mAmountGroup.check(R.id.donate_amt_5k);
        mAmountGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            int sats;
            if      (checkedId == R.id.donate_amt_1k)   sats = 1000;
            else if (checkedId == R.id.donate_amt_5k)   sats = 5000;
            else if (checkedId == R.id.donate_amt_21k)  sats = 21000;
            else if (checkedId == R.id.donate_amt_100k) sats = 100000;
            else return;
            mSelectedSats = sats;
            mCustomSatsInput.setText("");
            updatePayButtonLabel();
            hideInvoiceFallback();
        });

        mCustomSatsInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String txt = s.toString().trim();
                if (txt.isEmpty()) return;
                try {
                    int val = Integer.parseInt(txt);
                    if (val > 0) {
                        mSelectedSats = val;
                        mAmountGroup.clearChecked();
                        updatePayButtonLabel();
                        hideInvoiceFallback();
                    }
                } catch (NumberFormatException ignored) {}
            }
        });

        mPayButton.setOnClickListener(v -> onPayClicked());
        updatePayButtonLabel();
    }

    private void updatePayButtonLabel() {
        String formatted = NumberFormat.getNumberInstance(Locale.getDefault())
                .format(mSelectedSats);
        mPayButton.setText(getString(R.string.donate_pay_button, formatted));
    }

    private void onPayClicked() {
        // Dismiss the soft keyboard before generating the invoice. If the
        // user was typing a custom amount, the IME would otherwise stay
        // up and cover the QR + "open in wallet" / "copy" buttons that
        // appear after the invoice arrives — exactly the views the user
        // needs to act on next.
        dismissKeyboard();

        mPayButton.setEnabled(false);
        hideInvoiceFallback();
        setStatus(R.string.donate_status_generating, false);

        mInvoiceFetcher.fetchInvoice(LIGHTNING_ADDRESS, mSelectedSats, new LightningInvoiceFetcher.Callback() {
            @Override
            public void onSuccess(@NonNull String bolt11Invoice) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    mPayButton.setEnabled(true);
                    if (tryOpenInWallet(bolt11Invoice)) {
                        setStatus(R.string.donate_status_wallet_opened, false);
                    }
                    showInvoiceFallback(bolt11Invoice);
                });
            }

            @Override
            public void onError(@NonNull String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    mPayButton.setEnabled(true);
                    setStatus(getString(R.string.donate_status_error, message), true);
                });
            }
        });
    }

    private boolean tryOpenInWallet(@NonNull String bolt11) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("lightning:" + bolt11));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "No Lightning wallet app installed", e);
            return false;
        }
    }

    private void showInvoiceFallback(@NonNull String bolt11) {
        mInvoiceFallbackWrap.setVisibility(View.VISIBLE);

        Bitmap qr = generateQr("lightning:" + bolt11.toUpperCase(Locale.ROOT), 480);
        if (qr != null) mInvoiceQrImage.setImageBitmap(qr);

        mCopyInvoiceButton.setOnClickListener(v -> {
            copyToClipboard("lightning_invoice", bolt11);
            Snackbar.make(requireView(), R.string.donate_invoice_copied,
                    Snackbar.LENGTH_SHORT).show();
        });

        mOpenWalletButton.setOnClickListener(v -> {
            if (!tryOpenInWallet(bolt11)) {
                Snackbar.make(requireView(), R.string.donate_no_lightning_wallet,
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void hideInvoiceFallback() {
        mInvoiceFallbackWrap.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────
    // Bitcoin on-chain
    // ─────────────────────────────────────────────────────────────────

    private void setupBitcoinSection(@NonNull MaterialButton copyBtn,
                                     @NonNull MaterialButton walletBtn) {
        mDisplayedBtcAddress = mBtcProvider.getCachedAddress();

        mBtcAddressText.setText(mDisplayedBtcAddress);

        Bitmap qr = generateQr("bitcoin:" + mDisplayedBtcAddress, 480);
        if (qr != null) mBtcQrImage.setImageBitmap(qr);

        copyBtn.setOnClickListener(v -> {
            copyToClipboard("bitcoin_address", mDisplayedBtcAddress);
            Snackbar.make(requireView(), R.string.donate_btc_copied,
                    Snackbar.LENGTH_SHORT).show();
            mBtcProvider.onAddressUsed();
        });

        walletBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("bitcoin:" + mDisplayedBtcAddress));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                mBtcProvider.onAddressUsed();
            } catch (ActivityNotFoundException e) {
                Snackbar.make(requireView(), R.string.donate_no_bitcoin_wallet,
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }


    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private void setStatus(int resId, boolean isError) {
        setStatus(getString(resId), isError);
    }

    private void setStatus(@NonNull String text, boolean isError) {
        mStatusText.setText(text);
        int colorAttr = isError
                ? android.R.attr.colorPrimary
                : android.R.attr.textColorSecondary;
        TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(colorAttr, tv, true);
        mStatusText.setTextColor(tv.data);
    }


    /**
     * Hide the soft keyboard and drop focus from the custom-amount input.
     * Called when the user taps Send — the IME would otherwise cover
     * the QR and action buttons that appear once the invoice arrives.
     * Clearing focus too prevents the input from re-summoning the IME
     * the moment the layout settles.
     */
    private void dismissKeyboard() {
        View focused = mCustomSatsInput;
        if (focused == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }


    private void copyToClipboard(@NonNull String label, @NonNull String text) {
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    @Nullable
    private Bitmap generateQr(@NonNull String content, int sizePx) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            com.google.zxing.common.BitMatrix matrix =
                    writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth(), h = matrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (WriterException e) {
            Log.w(TAG, "QR generation failed", e);
            return null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mInvoiceFetcher != null) mInvoiceFetcher.cancel();
    }
}