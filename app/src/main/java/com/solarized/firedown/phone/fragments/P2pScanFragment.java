package com.solarized.firedown.phone.fragments;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;

import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.ClipboardHelper;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QR scanner for the P2P share codes — CameraX preview + a zxing decode on
 * the analysis stream, with a clipboard-paste fallback so the flow works
 * without granting the camera permission at all.
 *
 * <p><b>It is a full-screen {@link DialogFragment}, NOT a plain fragment</b>
 * (a {@code <dialog>} nav destination). This is load-bearing: navigating to a
 * sibling {@code <fragment>} destination would destroy the send/receive
 * fragment's view → {@code P2pShareBaseFragment.onDestroyView} →
 * {@code controller.stop()}, killing the live WebRTC session the scan is part
 * of, and the returning fragment would mint a fresh offer that the scanned
 * answer can never match. A dialog shows on top, leaving the caller's view —
 * and its session — intact. Don't turn this back into a {@code <fragment>}.
 *
 * <p>The camera permission is requested lazily, only here — this screen is
 * the app's ONLY camera use.
 *
 * <p>Result contract: the accepted code (validated against the expected
 * FDS1./FDR1. prefix in {@link #ARG_PREFIX}) is set on the PREVIOUS
 * back-stack entry's SavedStateHandle under {@link #RESULT_CODE}, then the
 * dialog pops — the CancelOperationDialogFragment pattern.
 */
public class P2pScanFragment extends DialogFragment {

    private static final String TAG = P2pScanFragment.class.getSimpleName();

    public static final String ARG_PREFIX = "p2p.scan.prefix";
    /** Optional title-resource-name toggle: true = "Scan reply". */
    public static final String ARG_REPLY = "p2p.scan.reply";
    /**
     * Optional toolbar title string-resource override. Added for the Cloud
     * screen's recovery-code scan, which reuses this whole screen (camera,
     * permission, zxing decode, paste fallback, result contract) but is not a
     * P2P share, so "Scan code"/"Scan reply" would misname it. 0 = keep the
     * {@link #ARG_REPLY} behaviour, so every existing caller is unaffected.
     *
     * <p>Note the prefix contract already generalises: {@link #ARG_PREFIX}
     * defaults to "" and a recovery code carries no prefix, so the scan accepts
     * any decoded text and the CALLER validates it (a recovery code is only
     * verifiable by trying to decode it). That is why this screen needed a
     * title argument and nothing more.
     */
    public static final String ARG_TITLE_RES = "p2p.scan.title.res";
    public static final String RESULT_CODE = "p2p.scan.result";

    private View mView;
    private NavController mNavController;
    private String mExpectedPrefix = "";
    private ExecutorService mAnalysisExecutor;
    private ProcessCameraProvider mCameraProvider;
    private final MultiFormatReader mReader = new MultiFormatReader();
    // Reused across frames — a fresh ~1-2 MB byte[] per analyzed frame at
    // 15-30 fps is needless GC churn on the analysis thread. Re-allocated
    // only when the frame size changes (constant per camera bind).
    private byte[] mLuminance;
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

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.Firedown_FullScreenDialog);
        if (requireActivity() instanceof BaseActivity activity) {
            mNavController = activity.getNavController();
        }
        Bundle args = getArguments();
        if (args != null) {
            String prefix = args.getString(ARG_PREFIX);
            if (prefix != null) {
                mExpectedPrefix = prefix;
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_p2p_scan, container, false);

        Toolbar toolbar = mView.findViewById(R.id.toolbar);
        boolean reply = getArguments() != null && getArguments().getBoolean(ARG_REPLY, false);
        int titleRes = getArguments() == null ? 0 : getArguments().getInt(ARG_TITLE_RES, 0);
        if (titleRes != 0) {
            toolbar.setTitle(titleRes);
        } else {
            toolbar.setTitle(reply ? R.string.p2p_scan_reply : R.string.p2p_scan_code);
        }
        toolbar.setNavigationOnClickListener(v -> dismiss());

        // Edge-to-edge insets (enforced on 15+, where the dialog theme's
        // statusBarColor/navigationBarColor are ignored): keep the toolbar
        // below the status bar and the paste panel above the nav bar — the
        // same manual pattern P2pShareBaseFragment uses for its footer.
        View appbar = mView.findViewById(R.id.appbar_layout);
        ViewCompat.setOnApplyWindowInsetsListener(appbar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return windowInsets;
        });
        View footer = mView.findViewById(R.id.p2p_scan_footer);
        int basePadding = footer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(footer, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return windowInsets;
        });

        mView.findViewById(R.id.p2p_scan_paste).setOnClickListener(v -> pasteCode());
        return mView;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Fill the screen (windowIsFloating=false + explicit match_parent) so
        // the camera preview isn't a small floating card.
        if (getDialog() != null && getDialog().getWindow() != null) {
            Window window = getDialog().getWindow();
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
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
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "camera provider", e);
                }
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
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "camera bind", e);
            }
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
            // compact row-by-row when they are, into a reused buffer.
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            byte[] luminance = ensureLuminance(width * height);
            if (rowStride == width) {
                buffer.get(luminance, 0, Math.min(luminance.length, buffer.remaining()));
            } else {
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
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "analyze", e);
            }
        } finally {
            image.close();
        }
    }

    private byte[] ensureLuminance(int size) {
        if (mLuminance == null || mLuminance.length != size) {
            mLuminance = new byte[size];
        }
        return mLuminance;
    }

    private void handleDecoded(@Nullable String text) {
        if (text == null) {
            return;
        }
        // A deep-linked offer QR reads as firedown://p2p/FDS1.… — unwrap it so
        // the FDS1./FDR1. prefix check below matches (bare codes pass through).
        String code = P2pShareController.stripDeepLink(text);
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
        String code = P2pShareController.stripDeepLink(ClipboardHelper.readTrimmedText(getContext()));
        if (code.isEmpty()) {
            snack(R.string.p2p_clipboard_empty);
            return;
        }
        if (!code.startsWith(mExpectedPrefix)) {
            snack(R.string.p2p_error_bad_code);
            return;
        }
        if (!mDelivered) {
            mDelivered = true;
            deliver(code);
        }
    }

    private void deliver(@NonNull String code) {
        if (mNavController != null) {
            NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
            if (previous != null) {
                previous.getSavedStateHandle().set(RESULT_CODE, code);
            }
        }
        dismiss();
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
