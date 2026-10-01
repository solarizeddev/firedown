package com.solarized.firedown.settings;

import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
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
 * <p>A whole nav destination rather than a dialog (it was one, briefly): a
 * dialog's ~270dp left a 4-line box to write in and cut both prompts
 * mid-sentence. Only success leaves the page (back to Settings, with a
 * snackbar); a failure keeps the typed text and states the error in the
 * note's place. While a send is in flight the button is disabled and Back is
 * swallowed, so a double tap can't post twice and leaving can't orphan the
 * result.</p>
 */
@AndroidEntryPoint
public class FeedbackFragment extends Fragment {

    private static final String PATH = "/v1/feedback";

    /** Must match the server's {@code feedbackPoWResource} byte-for-byte. */
    private static final String POW_RESOURCE = "feedback";

    @Inject
    OkHttpClient mHttpClient;

    private TextInputLayout mMessageLayout;
    private TextInputEditText mMessage;
    private TextInputEditText mContact;
    private TextView mNote;
    private MaterialButton mSend;
    private int mNoteColor;
    private boolean mSending;

    private final OnBackPressedCallback mBlockBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            // In flight: stay until the send resolves.
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_feedback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Edge-to-edge activity: pad for the navigation bar AND the keyboard,
        // so Send and the focused field are never drawn under either.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mMessageLayout = view.findViewById(R.id.feedback_message_layout);
        mMessage = view.findViewById(R.id.feedback_message);
        mContact = view.findViewById(R.id.feedback_contact);
        mNote = view.findViewById(R.id.feedback_note);
        mSend = view.findViewById(R.id.feedback_send);
        mNoteColor = mNote.getCurrentTextColor();

        mSend.setOnClickListener(v -> send());
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), mBlockBack);
    }

    private void send() {
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
        setSending(true);
        AnonymousPost.send(mHttpClient, PATH, POW_RESOURCE, body, ok -> {
            if (!isAdded() || getView() == null) {
                return;
            }
            setSending(false);
            if (ok) {
                View anchor = requireActivity().findViewById(android.R.id.content);
                NavHostFragment.findNavController(this).popBackStack();
                if (anchor != null) {
                    Snackbar.make(anchor, R.string.feedback_sent, Snackbar.LENGTH_LONG).show();
                }
                return;
            }
            showFailure();
        });
    }

    private void setSending(boolean sending) {
        mSending = sending;
        mBlockBack.setEnabled(sending);
        mSend.setEnabled(!sending);
        mSend.setText(sending ? R.string.feedback_sending : R.string.feedback_send);
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
