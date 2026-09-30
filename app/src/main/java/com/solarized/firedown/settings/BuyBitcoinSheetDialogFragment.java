package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The "Don't have bitcoin?" sheet under the buy screen's rail picker: one
 * line on what happens (buy the amount by card in a wallet app, come back,
 * pay the Lightning invoice here) and two wallets that SELL sats by card,
 * each with an Install button (Play Store, web listing as the fallback), plus
 * the country caveat.
 *
 * <p>Copy and store links, never an on-ramp integration: no partner, no API
 * key, no merchant KYC on our side — the wallet's on-ramp identifies the
 * buyer, and settlement reaches the mint as ordinary Bitcoin. It is a HELP
 * sheet rather than a payment option on purpose: a third "Card" rail row was
 * built and rejected, because it quoted the very same Lightning invoice and
 * only added a paragraph — a payment option in shape, help text in
 * substance. Verify a wallet's buy feature before naming it here (Phoenix
 * was once named and has no on-ramp at all).
 */
@AndroidEntryPoint
public class BuyBitcoinSheetDialogFragment extends BaseBottomSheetDialogFragment {

    /** The formatted purchase price ("$10.00"), so the "buy" step can say
     *  how much to buy; absent before a plan tile is picked. */
    public static final String ARG_AMOUNT = "bb_amount";

    /** Play Store ids. Wallet of Satoshi's in-app buy (MoonPay) is Android-only,
     *  which is fine here; Strike sells by debit card in 36+ countries. */
    private static final String PKG_WALLET_OF_SATOSHI = "com.livingroomofsatoshi.wallet";
    private static final String PKG_STRIKE = "zapsolutions.strike";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_buy_bitcoin_sheet, container, false);
        Bundle args = getArguments();
        String amount = args != null ? args.getString(ARG_AMOUNT) : null;
        TextView buyStep = mView.findViewById(R.id.bb_step_buy);
        buyStep.setText(amount != null
                ? getString(R.string.buy_bitcoin_step_buy, amount)
                : getString(R.string.buy_bitcoin_step_buy_noamount));
        mView.findViewById(R.id.bb_install_wos)
                .setOnClickListener(v -> openStore(PKG_WALLET_OF_SATOSHI));
        mView.findViewById(R.id.bb_install_strike)
                .setOnClickListener(v -> openStore(PKG_STRIKE));
        return mView;
    }

    /** Play Store listing, falling back to the web listing on a device with
     *  no store app (de-Googled), and a snackbar when nothing can open even
     *  that. */
    private void openStore(String pkg) {
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        try {
            startActivity(market);
            return;
        } catch (ActivityNotFoundException ignored) {
            // No store app — try the web listing below.
        }
        Intent web = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
        try {
            startActivity(web);
        } catch (ActivityNotFoundException e) {
            if (mView != null) {
                Snackbar.make(mView, R.string.buy_bitcoin_no_store, Snackbar.LENGTH_SHORT).show();
            }
        }
    }
}
