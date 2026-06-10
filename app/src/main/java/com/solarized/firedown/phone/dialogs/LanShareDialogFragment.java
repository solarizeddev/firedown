package com.solarized.firedown.phone.dialogs;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.lanshare.LanShareServer;
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
 * <p>Reached from the Downloads options sheet's quick-action row, which only
 * exists for finished, non-safe entries — the vault can never get here (and
 * a defensive check below enforces that).
 */
public class LanShareDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String TAG = LanShareDialogFragment.class.getSimpleName();

    private DownloadEntity mDownloadEntity;
    private LanShareServer mServer;

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
        String ip = LanShareServer.getLocalIpv4();
        if (!file.exists() || !file.canRead() || ip == null) {
            // No LAN / unreadable file — tell the user and bail.
            if (mActivity != null) {
                makeSnackbar(R.string.lan_share_no_network);
            }
            dismissAllowingStateLoss();
            return null;
        }

        mView = inflater.inflate(R.layout.fragment_dialog_lan_share, container, false);

        LanShareServer.SharedFile shared = new LanShareServer.SharedFile(
                file.getName(), file, mDownloadEntity.getFileMimeType());
        mServer = new LanShareServer(Collections.singletonList(shared), Build.MODEL);
        try {
            mServer.start();
        } catch (Exception e) {
            Log.e(TAG, "share server failed to start", e);
            makeSnackbar(R.string.lan_share_no_network);
            dismissAllowingStateLoss();
            return mView;
        }

        String hostPort = ip + ":" + mServer.getPort();
        ((TextView) mView.findViewById(R.id.lan_share_url)).setText(hostPort);
        ((TextView) mView.findViewById(R.id.lan_share_pin_value)).setText(mServer.getPin());
        ((TextView) mView.findViewById(R.id.lan_share_file)).setText(String.format(Locale.ROOT,
                "%s · %s", file.getName(),
                StoragePaths.convertToStringRepresentation(file.length())));

        // The QR carries the PIN too — scanning authenticates in one hop;
        // the displayed short URL is for typing and still hits the PIN gate.
        ImageView qr = mView.findViewById(R.id.lan_share_qr);
        Bitmap code = encodeQr("http://" + hostPort + "/?pin=" + mServer.getPin());
        if (code != null) {
            qr.setImageBitmap(code);
        }

        mView.findViewById(R.id.lan_share_stop).setOnClickListener(v -> dismiss());

        // Keep the screen on while the QR/PIN are being shown across the room.
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        return mView;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        stopServer();
        super.onDismiss(dialog);
    }

    @Override
    public void onDestroyView() {
        stopServer();
        super.onDestroyView();
    }

    private void stopServer() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
    }

    private void makeSnackbar(int textRes) {
        if (mActivity == null) {
            return;
        }
        com.google.android.material.snackbar.Snackbar.make(
                mActivity.getSnackAnchorView(), textRes,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
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
