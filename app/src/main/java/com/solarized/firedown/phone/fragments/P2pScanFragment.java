package com.solarized.firedown.phone.fragments;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.NavigationUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * QR scanner for the P2P share codes — CameraX preview + a zxing decode on
 * the analysis stream, with a clipboard-paste fallback so the flow works
 * without granting the camera permission at all (a code that arrived over a
 * messenger is pasted, not scanned).
 *
 * <p>The camera permission is requested lazily, only here — this screen is
 * the app's ONLY camera use, and the browser itself never gains the
 * permission implicitly (site permission prompts stay OS-gated as before).
 *
 * <p>Result contract: the accepted code (validated against the expected
 * FDS1./FDR1. prefix passed in {@link #ARG_PREFIX}) is set on the PREVIOUS
 * back-stack entry's SavedStateHandle under {@link #RESULT_CODE}, then the
 * screen pops — the CancelOperationDialogFragment pattern.
 */
@AndroidEntryPoint
public class P2pScanFragment extends BaseFocusFragment {

    private static final String TAG = P2pScanFragment.class.getSimpleName();

    public static final String ARG_PREFIX = "p2p.scan.prefix";
    public static final String RESULT_CODE = "p2p.scan.result";

    private View mView;
    private String mExpectedPrefix = "";
    private ExecutorService mAnalysisExecutor;
    private ProcessCameraProvider mCameraProvider;
    private final MultiFormatReader mReader = new MultiFormatReader();
    /**
     * Set once a code is delivered — the analyzer keeps receiving frames
     * until unbind, and a double pop would corrupt the back stack.
     */
    private volatile boolean mDelivered;

    private final ActivityResultLauncher<String> mCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startCamera();
                        } else {
                            showCameraUnavailable();
                        }
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        if (args != null) {
            String prefix = args.getString(ARG_PREFIX);
            if (prefix != null) {
                mExpectedPrefix = prefix;
            }
        }

        mView = inflater.inflate(R.layout.fragment_p2p_scan, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);

        mView.findViewById(R.id.p2p_scan_paste).setOnClickListener(v -> pasteCode());

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mToolbar != null) {
            mToolbar.setNavigationOnClickListener(v ->
                    NavigationUtils.popBackStackSafe(mNavController, R.id.p2p_scan));
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            mCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    public void onDestroyView() {
        if (mCameraProvider != null) {
            mCameraProvider.unbindAll();
            mCameraProvider = null;
        }
        if (mAnalysisExecutor != null) {
            mAnalysisExecutor.shutdown();
            mAnalysisExecutor = null;
        }
        super.onDestroyView();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            // The view may be gone by the time the provider resolves.
            if (mView == null || getView() == null) {
                return;
            }
            try {
                mCameraProvider = future.get();
            } catch (Exception e) {
                Log.e(TAG, "camera provider", e);
                showCameraUnavailable();
                return;
            }
            bindCamera();
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void bindCamera() {
        PreviewView previewView = mView.findViewById(R.id.p2p_scan_preview);
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // KEEP_ONLY_LATEST: decoding is slower than the frame rate; stale
        // frames are useless for a live viewfinder.
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        mAnalysisExecutor = Executors.newSingleThreadExecutor();
        analysis.setAnalyzer(mAnalysisExecutor, this::analyzeFrame);

        try {
            mCameraProvider.unbindAll();
            mCameraProvider.bindToLifecycle(
                    getViewLifecycleOwner(), CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        } catch (Exception e) {
            Log.e(TAG, "camera bind", e);
            showCameraUnavailable();
        }
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            if (mDelivered) {
                return;
            }
            // zxing wants a flat luminance plane. Plane 0 of YUV_420_888 is
            // exactly that, but its rows may be padded (rowStride > width) —
            // compact row-by-row when they are.
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            byte[] luminance;
            if (rowStride == width) {
                luminance = new byte[buffer.remaining()];
                buffer.get(luminance);
            } else {
                luminance = new byte[width * height];
                for (int row = 0; row < height; row++) {
                    buffer.position(row * rowStride);
                    buffer.get(luminance, row * width, width);
                }
            }
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    luminance, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result;
            try {
                result = mReader.decodeWithState(bitmap);
            } catch (NotFoundException e) {
                return;
            } finally {
                mReader.reset();
            }
            handleDecoded(result.getText());
        } catch (Exception e) {
            // A single bad frame must not kill the analyzer thread.
            Log.e(TAG, "analyze", e);
        } finally {
            image.close();
        }
    }

    private void handleDecoded(@Nullable String text) {
        if (text == null) {
            return;
        }
        String code = text.trim();
        if (!code.startsWith(mExpectedPrefix)) {
            return;
        }
        if (mDelivered) {
            return;
        }
        mDelivered = true;
        View view = mView;
        if (view != null) {
            view.post(() -> deliver(code));
        }
    }

    private void pasteCode() {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.toString().trim().startsWith(mExpectedPrefix)) {
                    if (!mDelivered) {
                        mDelivered = true;
                        deliver(text.toString().trim());
                    }
                    return;
                }
                snack(R.string.p2p_error_bad_code);
                return;
            }
        } catch (RuntimeException e) {
            // Clipboard is a binder call — never fatal (AutoCompleteView lesson).
            Log.e(TAG, "clipboard read failed", e);
        }
        snack(R.string.p2p_clipboard_empty);
    }

    private void deliver(@NonNull String code) {
        if (mNavController == null) {
            return;
        }
        NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
        if (previous != null) {
            previous.getSavedStateHandle().set(RESULT_CODE, code);
        }
        NavigationUtils.popBackStackSafe(mNavController, R.id.p2p_scan);
    }

    private void showCameraUnavailable() {
        if (mView == null) {
            return;
        }
        mView.findViewById(R.id.p2p_scan_unavailable).setVisibility(View.VISIBLE);
    }

    private void snack(int textResId) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, textResId, Snackbar.LENGTH_LONG).show();
        }
    }
}
