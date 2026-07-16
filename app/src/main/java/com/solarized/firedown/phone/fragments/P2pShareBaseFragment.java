package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.ClipboardHelper;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

/**
 * Shared plumbing for the two P2P share screens (send / receive):
 *
 * <ul>
 *   <li><b>Session-scoped WebRTC enable.</b> The user's Settings toggle keeps
 *       {@code media.peerconnection.enabled} off by default (a browsing
 *       hardening). The share needs it, so the screen flips the RUNTIME pref
 *       on while it is open and restores the stored preference's value in
 *       onDestroyView — the persisted setting is never touched, and a small
 *       note on the screen discloses the temporary enable. Users who set
 *       WebRTC on globally see no note and no flip.</li>
 *   <li><b>Session lifetime = view lifetime</b> (the LAN-share contract this
 *       feature replaced kept): onDestroyView stops the engine session, the
 *       loopback server, and any partial file.</li>
 *   <li>QR encode, share-sheet/clipboard code exchange, scan-result
 *       observation, keep-screen-on.</li>
 * </ul>
 *
 * <p>Hilt: the {@code @Inject} fields live here (unannotated-base pattern);
 * the concrete fragments carry {@code @AndroidEntryPoint}.
 */
public abstract class P2pShareBaseFragment extends BaseFocusFragment {

    private static final String TAG = P2pShareBaseFragment.class.getSimpleName();

    @Inject
    protected P2pShareController mP2pController;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mToolbar != null) {
            mToolbar.setNavigationOnClickListener(v -> confirmThenClose());
        }
        // Back-press confirms only while a transfer is actually moving bytes,
        // so an accidental swipe-back mid-transfer doesn't silently discard it.
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        confirmThenClose();
                    }
                });
        // The footer sits below the scroll area — pad it clear of the nav bar.
        View footer = view.findViewById(R.id.p2p_footer);
        if (footer != null) {
            int basePadding = footer.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(footer, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        basePadding + bars.bottom);
                return windowInsets;
            });
        }
        observeScanResult();

        // Session-scoped WebRTC prefs, applied BEFORE onEngineReady (which
        // drives the controller to OPEN its fresh engine page) — a page loaded
        // before the writes are visible sees the old values:
        //  - media.peerconnection.enabled: ALWAYS on since the user toggle was
        //    removed (boot sets it; see the Preferences note). The write here
        //    is an idempotent belt-and-braces so a share can never race a
        //    cold boot's async pref write.
        //  - ice.obfuscate_host_addresses OFF: Firefox hides host candidates
        //    behind mDNS .local names, which Android peers can't resolve (no
        //    MulticastLock), so a same-LAN pair finds no host pair and ICE
        //    fails outright ("add a TURN server"). Off, the QR carries the
        //    real LAN IP — which the user is already physically handing to the
        //    peer. Always restored to ON in onDestroyView.
        mGeckoRuntimeHelper.setWebRTC(true)
                .then(unused -> mGeckoRuntimeHelper.setWebRtcIceObfuscation(false))
                .accept(unused ->
                        mMainHandler.post(() -> {
                            if (isAdded() && getView() != null) {
                                onEngineReady();
                            }
                        }));
    }

    /**
     * Called once the WebRTC pref is applied and it's safe to drive the
     * engine. Send starts its offer here; receive waits for a scanned code.
     */
    protected abstract void onEngineReady();

    protected void confirmThenClose() {
        if (mP2pController.isTransferActive()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.p2p_abandon_message)
                    .setPositiveButton(R.string.p2p_abandon_confirm, (d, w) -> close())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            close();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Keep the screen on: the QR is being shown across the room and a
        // transfer must not die to the lock screen.
        if (mActivity != null) {
            mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onPause() {
        if (mActivity != null) {
            mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        mP2pController.stop();
        // The share-session candidate exposure ends with the screen — browsing
        // keeps mDNS obfuscation. (WebRTC itself is always on; nothing to
        // restore since the toggle was removed.)
        mGeckoRuntimeHelper.setWebRtcIceObfuscation(true);
        super.onDestroyView();
    }

    /** The nav destination id of the concrete screen (for pop). */
    protected abstract int destinationId();

    /** A code arrived from the scanner (or its paste fallback). */
    protected abstract void onCodeScanned(@NonNull String code);

    protected void close() {
        NavigationUtils.popBackStackSafe(mNavController, destinationId());
    }

    /**
     * The scanner returns its result through the previous back-stack entry's
     * SavedStateHandle (the CancelOperationDialogFragment pattern). Observed
     * on the LiveData so the value survives the navigation round-trip.
     */
    private void observeScanResult() {
        if (mNavController == null) {
            return;
        }
        NavBackStackEntry entry = mNavController.getCurrentBackStackEntry();
        if (entry == null) {
            return;
        }
        entry.getSavedStateHandle().<String>getLiveData(P2pScanFragment.RESULT_CODE)
                .observe(getViewLifecycleOwner(), code -> {
                    if (code == null || code.isEmpty()) {
                        return;
                    }
                    // Consume: a config change must not re-deliver the code.
                    entry.getSavedStateHandle().set(P2pScanFragment.RESULT_CODE, "");
                    onCodeScanned(code);
                });
    }

    protected void navigateToScanner(@NonNull String expectedPrefix, boolean reply) {
        Bundle bundle = new Bundle();
        bundle.putString(P2pScanFragment.ARG_PREFIX, expectedPrefix);
        bundle.putBoolean(P2pScanFragment.ARG_REPLY, reply);
        NavigationUtils.navigateSafe(mNavController, R.id.p2p_scan, bundle);
    }

    /**
     * @return true if the QR rendered; false if the content was too large to
     * encode (the caller should fall back to "send code another way" rather
     * than leave a blank white box).
     */
    protected boolean setQr(@Nullable ImageView imageView, @NonNull String content) {
        if (imageView == null) {
            return false;
        }
        Bitmap code = encodeQr(content);
        if (code != null) {
            imageView.setImageBitmap(code);
            return true;
        }
        return false;
    }

    /** Offer the code through the share sheet (messenger, email, …). */
    protected void shareCode(@NonNull String code) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, code);
        startActivity(Intent.createChooser(intent, getString(R.string.p2p_share_code)));
    }

    /**
     * Paste fallback for a code that arrived over a messenger. Returns null
     * (with a snackbar) when the clipboard holds nothing usable.
     */
    @Nullable
    protected String readCodeFromClipboard(@NonNull String expectedPrefix) {
        // Accept both a bare code and a firedown://p2p/<code> deep link (a user
        // may copy either the code or the share-sheet link).
        String code = P2pShareController.stripDeepLink(ClipboardHelper.readTrimmedText(getContext()));
        if (code.isEmpty()) {
            makeSnack(R.string.p2p_clipboard_empty);
            return null;
        }
        if (!code.startsWith(expectedPrefix)) {
            makeSnack(R.string.p2p_error_bad_code);
            return null;
        }
        return code;
    }

    protected void makeSnack(int textResId) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, textResId, Snackbar.LENGTH_LONG).show();
        }
    }

    /** Map an engine error code onto the honest user-facing message. */
    protected int errorText(@NonNull String code) {
        if ("no-path".equals(code)) {
            // When a VPN is up on THIS device it is almost certainly what killed
            // the direct path (full-tunnel VPN = symmetric NAT the user doesn't
            // control), and unlike CGNAT it has a user-actionable fix — say it.
            return isVpnActive() ? R.string.p2p_error_no_path_vpn
                    : R.string.p2p_error_no_path;
        }
        if ("bad-code".equals(code)) {
            return R.string.p2p_error_bad_code;
        }
        return R.string.p2p_error_generic;
    }

    /**
     * True when the device's active network is a VPN. Best-effort: any failure
     * reads as "no VPN" so the hint machinery can never break a share. Binder
     * reads of connectivity state get the same defensive treatment as the
     * clipboard chip (a decorative affordance never gets to crash the app).
     */
    protected boolean isVpnActive() {
        try {
            Context context = getContext();
            if (context == null) {
                return false;
            }
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * One-time hint when the transfer is running but RELAYED and a VPN is up:
     * the user can get a direct connection next time by excluding Firedown
     * from the VPN (or pausing it). Called from both fragments' onTransport.
     * Once per share screen — the transfer works, so one nudge is enough;
     * repeating it on every transport event would be noise.
     */
    private boolean mVpnRelayHintShown;

    protected void maybeHintVpnRelay(boolean relayed) {
        if (!relayed || mVpnRelayHintShown || !isVpnActive()) {
            return;
        }
        mVpnRelayHintShown = true;
        makeSnack(R.string.p2p_hint_vpn);
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
            Log.e(TAG, "QR encode failed", e);
            return null;
        }
    }
}
