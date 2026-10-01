package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.crash.AnonymousPost;
import com.solarized.firedown.ui.IncognitoColors;

import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Settings → "Send feedback": a free-text note (plus an OPTIONAL reply-to the
 * user may type) POSTed anonymously to the api's {@code /v1/feedback} through
 * {@link AnonymousPost} — the crash send's flow, PoW gate included. The note
 * under the fields states exactly what travels (message, optional contact, app
 * version, Android version, phone model); if the payload ever changes, that
 * string changes with it in every locale.
 *
 * <p>The Send button is overridden after {@code show()} so a failed send
 * keeps the dialog — and the typed text — on screen with the failure stated
 * in place of the note; only success dismisses (then a snackbar thanks the
 * user on the activity behind). Buttons are disabled for the in-flight window
 * so a double tap can't post twice.</p>
 */
@AndroidEntryPoint
public class FeedbackDialogFragment extends DialogFragment {

    private static final String PATH = "/v1/feedback";

    /** Must match the server's {@code feedbackPoWResource} byte-for-byte. */
    private static final String POW_RESOURCE = "feedback";

    @Inject
    OkHttpClient mHttpClient;

    private TextInputLayout mMessageLayout;
    private TextInputEditText mMessage;
    private TextInputEditText mContact;
    private TextView mNote;
    private int mNoteColor;
    private boolean mSending;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_feedback, null, false);
        mMessageLayout = view.findViewById(R.id.feedback_message_layout);
        mMessage = view.findViewById(R.id.feedback_message);
        mContact = view.findViewById(R.id.feedback_contact);
        mNote = view.findViewById(R.id.feedback_note);
        mNoteColor = mNote.getCurrentTextColor();

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feedback_title)
                .setView(view)
                .setPositiveButton(R.string.feedback_send, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> send(dialog)));
        return dialog;
    }

    private void send(@NonNull AlertDialog dialog) {
        if (mSending) {
            return;
        }
        String message = textOf(mMessage);
        if (message.isEmpty()) {
            mMessageLayout.setError(getString(R.string.feedback_empty));
            return;
        }
        mMessageLayout.setError(null);
        JSONObject body = new JSONObject();
        try {
            body.put("message", message);
            body.put("contact", textOf(mContact));
            body.put("versionName", BuildConfig.VERSION_NAME);
            body.put("versionCode", BuildConfig.VERSION_CODE);
            body.put("sdk", Build.VERSION.SDK_INT);
            body.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        } catch (JSONException e) {
            showFailure();
            return;
        }
        setSending(dialog, true);
        AnonymousPost.send(mHttpClient, PATH, POW_RESOURCE, body, ok -> {
            if (!isAdded()) {
                return;
            }
            if (ok) {
                View anchor = requireActivity().findViewById(android.R.id.content);
                dismiss();
                if (anchor != null) {
                    Snackbar.make(anchor, R.string.feedback_sent, Snackbar.LENGTH_LONG).show();
                }
                return;
            }
            setSending(dialog, false);
            showFailure();
        });
    }

    private void setSending(@NonNull AlertDialog dialog, boolean sending) {
        mSending = sending;
        setCancelable(!sending);
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        positive.setEnabled(!sending);
        negative.setEnabled(!sending);
        positive.setText(sending ? R.string.feedback_sending : R.string.feedback_send);
        if (sending) {
            mNote.setText(R.string.feedback_note);
            mNote.setTextColor(mNoteColor);
        }
    }

    private void showFailure() {
        mNote.setText(R.string.feedback_failed);
        mNote.setTextColor(IncognitoColors.getError(requireContext(), false));
    }

    @NonNull
    private static String textOf(@Nullable TextInputEditText field) {
        if (field == null) {
            return "";
        }
        Editable text = field.getText();
        return text == null ? "" : text.toString().trim();
    }
}
