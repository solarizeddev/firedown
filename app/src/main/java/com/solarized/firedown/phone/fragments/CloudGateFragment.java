package com.solarized.firedown.phone.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.IOException;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cloud-backup interest gate — a demand test that runs BEFORE the product is
 * built. One static screen: the pitch and one button. The tap sends exactly
 * one anonymous request ({@code POST /v1/interest/continuity} — empty body,
 * no identifiers) whose full shape is disclosed on-screen ABOVE the fold; the
 * app ships no analytics, so this deliberate tap is the only signal that
 * exists. Viewing, dismissing, or leaving sends nothing.
 *
 * <p>Dedup is on-device only ({@link Preferences#CLOUD_GATE_COUNTED} in the
 * backed-up prefs, so a reinstall+restore doesn't recount) — server-side dedup
 * would need an identifier, which is refused by design. Counting also retires
 * the Downloads announce card (its visibility checks both flags).
 */
@AndroidEntryPoint
public class CloudGateFragment extends BaseFocusFragment {

    @Inject
    SharedPreferences mSharedPreferences;
    @Inject
    OkHttpClient mOkHttpClient;

    private View mView;
    // One in-flight ping at a time; the button re-enables on failure.
    private boolean mSending;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        mView = inflater.inflate(R.layout.fragment_cloud_gate, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(v ->
                NavigationUtils.popBackStackSafe(mNavController, R.id.cloud_gate));

        mView.findViewById(R.id.cloud_gate_vote).setOnClickListener(v -> sendInterest());
        applyState();

        return mView;
    }

    @Override
    public void onDestroyView() {
        mView = null;
        super.onDestroyView();
    }

    /** Button + note reflect the counted flag; idempotent. */
    private void applyState() {
        boolean counted = mSharedPreferences.getBoolean(Preferences.CLOUD_GATE_COUNTED, false);
        MaterialButton vote = mView.findViewById(R.id.cloud_gate_vote);
        vote.setEnabled(!counted && !mSending);
        vote.setText(counted ? R.string.cloud_gate_voted : R.string.cloud_gate_vote);
        mView.findViewById(R.id.cloud_gate_counted_note)
                .setVisibility(counted ? View.VISIBLE : View.GONE);
    }

    private void sendInterest() {
        if (mSending
                || mSharedPreferences.getBoolean(Preferences.CLOUD_GATE_COUNTED, false)) {
            return;
        }
        mSending = true;
        applyState();

        // The whole request, exactly as disclosed on-screen: an empty POST,
        // no body, no identifiers. OkHttp adds nothing beyond what HTTP needs.
        Request request = new Request.Builder()
                .url(Preferences.CLOUD_GATE_INTEREST_URL)
                .post(RequestBody.create(new byte[0], null))
                .build();
        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                deliverResult(false);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                boolean ok = response.isSuccessful();
                response.close();
                deliverResult(ok);
            }
        });
    }

    /** Hop to the main thread and reflect the outcome; safe after view death
     *  (the pref write still lands so the count isn't lost on a quick exit). */
    private void deliverResult(boolean counted) {
        if (counted) {
            mSharedPreferences.edit()
                    .putBoolean(Preferences.CLOUD_GATE_COUNTED, true)
                    .apply();
        }
        View view = mView;
        if (view == null) {
            return;
        }
        view.post(() -> {
            mSending = false;
            if (mView == null || !isAdded()) {
                return;
            }
            applyState();
            if (!counted) {
                makeSnackbar(mView, R.string.cloud_gate_error, false).show();
            }
        });
    }
}
