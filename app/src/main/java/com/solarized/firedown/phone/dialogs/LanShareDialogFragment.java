package com.solarized.firedown.phone.dialogs;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.lanshare.LanShareServer;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.StoragePaths;

import java.io.File;
import java.util.Collections;
import java.util.Locale;

/**
 * "Send to browser" sheet — the sender side of the LAN share (see
 * {@link LanShareServer} for the protocol and the threat model). Shows the
 * short URL, the QR (URL + PIN combined so a scan authenticates in one hop),
 * the PIN, and Stop.
 *
 * <p><b>Sharing ends when the sheet does</b>: the server is started in
 * onCreateView and stopped in onDismiss/onDestroyView — there is no
 * background sharing state to forget about. The screen is kept on while the
 * sheet shows so the QR/PIN don't black out mid-handover.
 *
 * <p><b>No usable LAN (both devices on cellular — the bar scenario)</b>: the
 * sheet offers a <i>direct connection</i> instead of dead-ending — a
 * {@code LocalOnlyHotspot} this phone raises (no internet upstream, lives
 * only as long as the sheet). Step 1 shows a standard {@code WIFI:} QR the
 * receiver's camera joins from; Next flips to the normal URL/PIN QR served
 * on the hotspot's AP address. Needs fine location at runtime (an Android
 * platform requirement for hotspot APIs, requested only here). A
 * receiver-side VPN can still null-route the transfer and the sender cannot
 * detect that; the sender's own VPN at least gets a warning line.
 *
 * <p>Reached from the Downloads options sheet's quick-action row, which only
 * exists for finished, non-safe entries — the vault can never get here (and
 * a defensive check below enforces that).
 */
public class LanShareDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String TAG = LanShareDialogFragment.class.getSimpleName();

    private DownloadEntity mDownloadEntity;
    private LanShareServer mServer;
    private LanShareServer.SharedFile mSharedFile;
    private WifiManager.LocalOnlyHotspotReservation mHotspotReservation;
    /**
     * The Wi-Fi STA address at sheet-open time, or null. Passed to
     * {@link LanShareServer#getHotspotIpv4} so the hotspot step can tell the
     * AP interface apart from a still-connected STA (the "use direct
     * connection instead" escape keeps Wi-Fi up).
     */
    private String mPreHotspotIp;

    private final ActivityResultLauncher<String> mLocationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startHotspot();
                        } else {
                            makeSnackbar(R.string.lan_direct_failed);
                        }
                    });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDownloadEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, DownloadEntity.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // Defensive: vault entries must never be shareable, and a restored
        // sheet without its entity has nothing to share.
        if (mDownloadEntity == null || mDownloadEntity.isFileSafe()
                || mDownloadEntity.getFilePath() == null) {
            dismissAllowingStateLoss();
            return null;
        }

        File file = new File(mDownloadEntity.getFilePath());
        if (!file.exists() || !file.canRead()) {
            if (mActivity != null) {
                makeSnackbar(R.string.lan_share_no_network);
            }
            dismissAllowingStateLoss();
            return null;
        }

        mView = inflater.inflate(R.layout.fragment_dialog_lan_share, container, false);

        // Derive the served Content-Type from the saved file's extension (the
        // bytes on disk are ground truth), not the entity's stored mime label
        // — that label is Firedown's internal/UI value and serves a wrong or
        // unusable Content-Type to the receiving browser.
        String mime = FileUriHelper.getMimeTypeFromFile(file.getName());
        mSharedFile = new LanShareServer.SharedFile(file.getName(), file, mime);

        ((TextView) mView.findViewById(R.id.lan_share_file)).setText(String.format(Locale.ROOT,
                "%s · %s", file.getName(),
                StoragePaths.convertToStringRepresentation(file.length())));

        String ip = LanShareServer.getLocalIpv4();
        if (ip != null) {
            mPreHotspotIp = ip;
            if (!startSharing(ip)) {
                return mView;
            }
            if (isVpnActive()) {
                // Sender-side VPN: sockets on the LAN usually still work,
                // but block-LAN/kill-switch configs break them — warn, don't
                // block. (A RECEIVER-side VPN is invisible from here.)
                mView.findViewById(R.id.lan_share_vpn).setVisibility(View.VISIBLE);
            }
        } else {
            // No LAN (cellular-only, Wi-Fi off): offer the hotspot direct
            // connection instead of a dead-end snackbar.
            showDirectIntro(true);
        }

        mView.findViewById(R.id.lan_share_stop).setOnClickListener(v -> dismiss());

        // Keep the screen on while the QR/PIN are being shown across the room.
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        return mView;
    }

    /**
     * Start the HTTP server and populate the URL/QR/PIN views. Returns false
     * when the server couldn't bind (sheet is dismissing).
     */
    private boolean startSharing(@NonNull String ip) {
        if (mServer != null) {
            // Double-tap on Next — the first call already owns the server.
            return true;
        }
        mServer = new LanShareServer(Collections.singletonList(mSharedFile), Build.MODEL,
                requireContext().getAssets());
        try {
            mServer.start();
        } catch (Exception e) {
            Log.e(TAG, "share server failed to start", e);
            makeSnackbar(R.string.lan_share_no_network);
            dismissAllowingStateLoss();
            return false;
        }

        String hostPort = ip + ":" + mServer.getPort();
        ((TextView) mView.findViewById(R.id.lan_share_url)).setText(hostPort);

        // Reset the texts/visibility the hotspot join step may have repurposed.
        TextView lead = mView.findViewById(R.id.lan_share_lead);
        lead.setText(R.string.lan_share_lead);
        lead.setVisibility(View.VISIBLE);
        ((TextView) mView.findViewById(R.id.lan_share_hint)).setText(R.string.lan_share_qr_hint);
        ((TextView) mView.findViewById(R.id.lan_share_pin_label)).setText(R.string.lan_share_pin);
        TextView pin = mView.findViewById(R.id.lan_share_pin_value);
        pin.setText(mServer.getPin());
        pin.setTextSize(30);
        pin.setLetterSpacing(0.45f);

        // The QR carries the PIN too — scanning authenticates in one hop;
        // the displayed short URL is for typing and still hits the PIN gate.
        //
        // PLAIN http on purpose — do not "upgrade" this to an intent:// URI
        // to prefer Firedown on the receiver. intent:// is a Chrome-ism for
        // links navigated INSIDE a browser page; a camera/QR scanner
        // resolves it as a literal scheme no app handles, and Samsung's
        // scanner showed "no app can handle this" (tested on-device). A
        // plain http URL is the only payload every scanner opens, and the
        // receiver's default browser is fine — the page carries the
        // Get-the-app link for the Firedown pitch instead.
        setQr("http://" + hostPort + "/?pin=" + mServer.getPin());

        mView.findViewById(R.id.lan_share_content).setVisibility(View.VISIBLE);
        mView.findViewById(R.id.lan_direct_group).setVisibility(View.GONE);
        mView.findViewById(R.id.lan_share_next).setVisibility(View.GONE);

        // AP-isolation escape (LAN mode only): guest Wi-Fi that blocks
        // client-to-client traffic looks fine from here but the receiver
        // times out — undetectable sender-side, so offer the hotspot as a
        // manual way out. Pointless once already ON the hotspot.
        View useDirect = mView.findViewById(R.id.lan_share_use_direct);
        if (mHotspotReservation == null) {
            useDirect.setVisibility(View.VISIBLE);
            useDirect.setOnClickListener(v -> switchToDirect());
        } else {
            useDirect.setVisibility(View.GONE);
        }
        return true;
    }

    /** "Use direct connection instead" — leave LAN mode for the hotspot flow. */
    private void switchToDirect() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        mView.findViewById(R.id.lan_share_use_direct).setVisibility(View.GONE);
        mView.findViewById(R.id.lan_share_vpn).setVisibility(View.GONE);
        showDirectIntro(false);
    }

    // ------------------------------------------------------------------
    // Direct connection (LocalOnlyHotspot) — the no-common-LAN path
    // ------------------------------------------------------------------

    /**
     * @param noNetwork true when entering because there is no LAN at all
     *                  (the lead explains that); false when the user chose
     *                  the direct connection over a working LAN (no claim
     *                  about missing Wi-Fi — hide the lead).
     */
    private void showDirectIntro(boolean noNetwork) {
        TextView lead = mView.findViewById(R.id.lan_share_lead);
        if (noNetwork) {
            lead.setText(R.string.lan_share_no_network);
            lead.setVisibility(View.VISIBLE);
        } else {
            lead.setVisibility(View.GONE);
        }
        mView.findViewById(R.id.lan_share_content).setVisibility(View.GONE);
        mView.findViewById(R.id.lan_direct_group).setVisibility(View.VISIBLE);
        mView.findViewById(R.id.lan_direct_start).setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                startHotspot();
            } else {
                mLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        });
    }

    private void startHotspot() {
        WifiManager wifiManager = (WifiManager) requireContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            makeSnackbar(R.string.lan_direct_failed);
            return;
        }
        mView.findViewById(R.id.lan_direct_start).setEnabled(false);
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    if (!isAdded() || mView == null) {
                        // Sheet died while the hotspot was coming up.
                        reservation.close();
                        return;
                    }
                    mHotspotReservation = reservation;
                    showHotspotJoinStep(reservation);
                }

                @Override
                public void onFailed(int reason) {
                    Log.e(TAG, "hotspot failed: " + reason);
                    if (isAdded() && mView != null) {
                        mView.findViewById(R.id.lan_direct_start).setEnabled(true);
                        makeSnackbar(R.string.lan_direct_failed);
                    }
                }

                @Override
                public void onStopped() {
                    // The system tore the hotspot down (e.g. the user turned
                    // Wi-Fi on) — sharing over it is over either way.
                    mHotspotReservation = null;
                    if (isAdded()) {
                        makeSnackbar(R.string.lan_direct_failed);
                        dismissAllowingStateLoss();
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException | IllegalStateException e) {
            // Location services off, hotspot already held by another app, or
            // tethering restricted by policy.
            Log.e(TAG, "startLocalOnlyHotspot rejected", e);
            mView.findViewById(R.id.lan_direct_start).setEnabled(true);
            makeSnackbar(R.string.lan_direct_failed);
        }
    }

    /**
     * Step 1: repurpose the share views as the hotspot join screen — URL
     * chip = network name, PIN slot = password, QR = standard {@code WIFI:}
     * payload every camera app can join from. Next flips to the URL/PIN.
     */
    private void showHotspotJoinStep(@NonNull WifiManager.LocalOnlyHotspotReservation reservation) {
        String ssid = null;
        String passphrase = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SoftApConfiguration config = reservation.getSoftApConfiguration();
            ssid = config.getSsid();
            passphrase = config.getPassphrase();
        } else {
            WifiConfiguration config = reservation.getWifiConfiguration();
            if (config != null) {
                ssid = stripQuotes(config.SSID);
                passphrase = config.preSharedKey;
            }
        }
        if (ssid == null || passphrase == null) {
            makeSnackbar(R.string.lan_direct_failed);
            dismissAllowingStateLoss();
            return;
        }

        // The join instruction lives under the QR; a lead saying the same
        // thing above it is noise — hide it for this step.
        mView.findViewById(R.id.lan_share_lead).setVisibility(View.GONE);
        ((TextView) mView.findViewById(R.id.lan_share_url)).setText(ssid);
        ((TextView) mView.findViewById(R.id.lan_share_hint))
                .setText(R.string.lan_direct_join_hint);
        ((TextView) mView.findViewById(R.id.lan_share_pin_label))
                .setText(R.string.lan_direct_password);

        // The passphrase is much longer than the 4-digit PIN — drop the
        // display size/tracking so it fits one line.
        TextView pin = mView.findViewById(R.id.lan_share_pin_value);
        pin.setText(passphrase);
        pin.setTextSize(20);
        pin.setLetterSpacing(0.1f);

        setQr("WIFI:T:WPA;S:" + escapeWifiQr(ssid) + ";P:" + escapeWifiQr(passphrase) + ";;");

        mView.findViewById(R.id.lan_direct_group).setVisibility(View.GONE);
        mView.findViewById(R.id.lan_share_content).setVisibility(View.VISIBLE);
        View next = mView.findViewById(R.id.lan_share_next);
        next.setVisibility(View.VISIBLE);
        next.setOnClickListener(v -> {
            // Hotspot-aware lookup: when the user switched here FROM a
            // working Wi-Fi, the STA keeps its address — which a hotspot
            // client can't reach — so the AP interface must win (told apart
            // by name, or by differing from the pre-hotspot STA address).
            String ip = LanShareServer.getHotspotIpv4(mPreHotspotIp);
            if (ip == null) {
                makeSnackbar(R.string.lan_direct_failed);
                return;
            }
            startSharing(ip);
        });
    }

    // ------------------------------------------------------------------

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        stopSharing();
        super.onDismiss(dialog);
    }

    @Override
    public void onDestroyView() {
        stopSharing();
        super.onDestroyView();
    }

    private void stopSharing() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        if (mHotspotReservation != null) {
            mHotspotReservation.close();
            mHotspotReservation = null;
        }
    }

    private void makeSnackbar(int textRes) {
        if (mActivity == null) {
            return;
        }
        Snackbar.make(
                mActivity.getSnackAnchorView(), textRes,
                Snackbar.LENGTH_LONG).show();
    }

    private boolean isVpnActive() {
        ConnectivityManager manager = (ConnectivityManager) requireContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void setQr(@NonNull String content) {
        ImageView qr = mView.findViewById(R.id.lan_share_qr);
        Bitmap code = encodeQr(content);
        if (code != null) {
            qr.setImageBitmap(code);
        }
    }

    /** Escape the {@code WIFI:} QR payload's special characters per the de-facto spec. */
    @NonNull
    private static String escapeWifiQr(@NonNull String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    /** Pre-R {@link WifiConfiguration#SSID} comes back wrapped in quotes. */
    @Nullable
    private static String stripQuotes(@Nullable String ssid) {
        if (ssid != null && ssid.length() >= 2
                && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
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
