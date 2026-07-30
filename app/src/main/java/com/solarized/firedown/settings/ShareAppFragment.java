package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.QrCodes;

/**
 * "Share Firedown" — a QR of the download page plus a share-sheet link.
 *
 * <p>Two registers on one page, the same in-person/remote split the P2P share
 * screens are built around: the QR is the IN-PERSON handover (the other phone's
 * camera reads it off this screen — no messenger, no network) and the button is
 * the REMOTE one. The button therefore shares the URL as TEXT, not the QR as an
 * image: a chat app linkifies a URL into one tap, whereas an image has to be
 * scanned off a second screen, and most messengers drop the accompanying text
 * once a picture is attached.
 *
 * <p>No logo is composited into the middle of the QR (the shape this was
 * modelled on does that). {@link QrCodes} encodes at zxing's default error
 * correction, which tolerates far too little occlusion to punch a hole in the
 * centre; raising the level to carry a logo would change the encoder for its
 * three other callers — the P2P codes, the Lightning invoice and the recovery
 * code — to buy decoration.
 *
 * <p>A whole nav destination rather than a dialog, deliberately: blurb + QR +
 * URL + caption + button is exactly the height that made the recovery-code
 * dialog taller than the window and clipped its stacked button panel.
 */
public class ShareAppFragment extends Fragment {

    private static final String TAG = ShareAppFragment.class.getName();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_share_app, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // The activity draws edge-to-edge, so without this the bottom button
        // sits under the navigation bar (it did). Padding the ScrollView shrinks
        // the viewport, which is what the fillViewport column measures against.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        String url = getString(R.string.share_app_url);

        TextView urlView = view.findViewById(R.id.share_app_url);
        urlView.setText(getString(R.string.share_app_url_display));

        bindQr(view.findViewById(R.id.share_app_qr), url);

        MaterialButton share = view.findViewById(R.id.share_app_share);
        share.setOnClickListener(v -> shareLink(url));
    }

    /**
     * Renders the QR with the app icon on a tile in the middle. Falls back to
     * the plain code if the launcher foreground can't be loaded, and hides the
     * view outright if the encoder declines the payload — an empty white ground
     * reads as a broken scan target, and the link plus the share button carry
     * the screen without it.
     */
    private void bindQr(@NonNull ImageView qr, @NonNull String url) {
        Bitmap code = null;
        Drawable logo = AppCompatResources.getDrawable(
                requireContext(), R.drawable.ic_launcher_foreground);
        if (logo != null) {
            code = QrCodes.encodeWithLogo(url, logo,
                    ContextCompat.getColor(requireContext(), R.color.share_app_qr_logo_ground));
        }
        if (code == null) {
            code = QrCodes.encode(url);
        }
        if (code != null) {
            qr.setImageBitmap(code);
        } else {
            qr.setVisibility(View.GONE);
        }
    }

    /** Hands the download link to the share sheet (messenger, mail, copy…). */
    private void shareLink(@NonNull String url) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_message, url));
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_app_title)));
        } catch (ActivityNotFoundException e) {
            // A device with nothing able to receive text/plain. Rare, but the
            // chooser throwing must not take the screen down with it.
            View root = getView();
            if (root != null) {
                Snackbar.make(root, R.string.error_general, Snackbar.LENGTH_LONG).show();
            }
        }
    }
}
