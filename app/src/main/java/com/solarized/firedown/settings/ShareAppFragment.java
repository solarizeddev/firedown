package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

        String url = getString(R.string.share_app_url);

        TextView urlView = view.findViewById(R.id.share_app_url);
        urlView.setText(getString(R.string.share_app_url_display));

        ImageView qr = view.findViewById(R.id.share_app_qr);
        Bitmap code = QrCodes.encode(url);
        if (code != null) {
            qr.setImageBitmap(code);
        } else {
            // The encoder reports failure as null; showing the empty white
            // ground would read as a broken scan target. The link + the share
            // button still carry the whole screen without it.
            qr.setVisibility(View.GONE);
        }

        MaterialButton share = view.findViewById(R.id.share_app_share);
        share.setOnClickListener(v -> shareLink(url));
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
