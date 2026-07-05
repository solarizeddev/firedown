package com.solarized.firedown.nostr;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.nostr.NostrSignerBridge.NostrRequest;

import org.json.JSONObject;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Transparent, no-UI (except one permission dialog) proxy activity that runs a
 * single NIP-55 signer round-trip and hands the result back to
 * {@link NostrSignerBridge}.
 *
 * <p><b>Why a separate activity, not BrowserActivity:</b> BrowserActivity is
 * {@code launchMode="singleTask"}, and {@code startActivityForResult} from a
 * singleTask activity can silently deliver {@code RESULT_CANCELED} (the result
 * is lost across the task boundary). A dedicated standard-launch-mode activity
 * owns the ActivityResult round-trip cleanly.</p>
 *
 * <p>The bridge launches exactly one of these at a time (its FIFO queue), so
 * this activity always handles the single request identified by
 * {@link #EXTRA_KEY}.</p>
 */
@AndroidEntryPoint
public class NostrSignerActivity extends AppCompatActivity {

    private static final String TAG = "NostrSigner";
    static final String EXTRA_KEY = "nostr_request_key";

    @Inject
    NostrSignerBridge mBridge;

    private long mKey = -1;
    private NostrRequest mRequest;
    private boolean mDelivered = false;

    private final ActivityResultLauncher<Intent> mSignerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::onSignerResult);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mKey = getIntent().getLongExtra(EXTRA_KEY, -1);
        mRequest = mKey >= 0 ? mBridge.getRequest(mKey) : null;
        if (mRequest == null) {
            // Nothing to do (already delivered, or lost to process death).
            finish();
            return;
        }

        Boolean permission = mBridge.getPermission(mRequest.origin);

        // Recreated by a config change NOT covered by the manifest's
        // configChanges list (rotation IS covered, so it won't recreate us). If
        // the signer was already launched, the ActivityResult API re-delivers
        // its result to the re-registered launcher — we must NOT re-launch. The
        // only thing to restore is the permission dialog, and only if the
        // decision is still pending (permission unknown means the dialog was
        // the thing on screen). A resolved permission means we already launched
        // (allow) or finished (deny), so do nothing and await the launcher.
        if (savedInstanceState != null) {
            if (permission == null) {
                showPermissionDialog();
            }
            return;
        }

        if (permission != null && !permission) {
            deliverError("user rejected");
        } else if (permission != null) {
            launchSigner();
        } else {
            showPermissionDialog();
        }
    }

    private void showPermissionDialog() {
        String host = Uri.parse(mRequest.origin).getHost();
        if (host == null) {
            host = mRequest.origin;
        }
        final String fHost = host;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.nostr_permission_title)
                .setMessage(getString(R.string.nostr_permission_message, fHost))
                .setCancelable(true)
                .setPositiveButton(R.string.nostr_permission_allow, (d, w) -> {
                    mBridge.setPermission(mRequest.origin, true);
                    launchSigner();
                })
                .setNegativeButton(R.string.nostr_permission_deny, (d, w) -> {
                    mBridge.setPermission(mRequest.origin, false);
                    deliverError("user rejected");
                })
                .setOnCancelListener(d -> deliverError("user rejected"))
                .show();
    }

    private void launchSigner() {
        Intent intent = buildSignerIntent();
        if (intent == null) {
            deliverError("unsupported request");
            return;
        }
        try {
            mSignerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.nostr_no_signer, Toast.LENGTH_LONG).show();
            deliverError("no signer app installed");
        }
    }

    /**
     * Builds the NIP-55 intent for this request. Amber reads the operation from
     * the {@code type} extra; the data URI carries the payload to sign/encrypt;
     * {@code current_user} (cached pubkey) lets Amber skip the account picker.
     */
    @Nullable
    private Intent buildSignerIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage(NostrSignerBridge.SIGNER_PACKAGE);
        intent.putExtra("type", nip55Type(mRequest.type));
        intent.putExtra("id", String.valueOf(mRequest.key));

        String cachedPubkey = mBridge.getCachedPubkey();
        if (cachedPubkey != null) {
            intent.putExtra("current_user", cachedPubkey);
        }

        switch (mRequest.type) {
            case "get_public_key":
                intent.setData(Uri.parse("nostrsigner:"));
                break;
            case "sign_event":
                if (mRequest.eventJson == null) {
                    return null;
                }
                intent.setData(Uri.parse("nostrsigner:" + Uri.encode(mRequest.eventJson)));
                break;
            case "nip04_encrypt":
            case "nip04_decrypt":
            case "nip44_encrypt":
            case "nip44_decrypt":
                if (mRequest.pubkey == null || mRequest.payload == null) {
                    return null;
                }
                intent.setData(Uri.parse("nostrsigner:" + Uri.encode(mRequest.payload)));
                intent.putExtra("pubkey", mRequest.pubkey);
                break;
            default:
                return null;
        }
        return intent;
    }

    private void onSignerResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            deliverError("user rejected");
            return;
        }
        Intent data = result.getData();
        switch (mRequest.type) {
            case "get_public_key":
                handlePublicKey(data.getStringExtra("result"));
                break;
            case "sign_event":
                handleSignedEvent(data.getStringExtra("event"));
                break;
            default: // nip04/nip44 — cipher/plaintext in "signature" or "result"
                String out = data.getStringExtra("signature");
                if (out == null) {
                    out = data.getStringExtra("result");
                }
                if (out == null) {
                    deliverError("signer returned no result");
                } else {
                    deliver(NostrSignerBridge.okString(out));
                }
                break;
        }
    }

    private void handlePublicKey(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            deliverError("signer returned no public key");
            return;
        }
        String hex = normalizePubkey(raw);
        if (hex == null) {
            deliverError("signer returned an unreadable public key");
            return;
        }
        mBridge.setCachedPubkey(hex);
        deliver(NostrSignerBridge.okString(hex));
    }

    private void handleSignedEvent(@Nullable String eventJson) {
        // NIP-07 signEvent must resolve to the FULL signed event. Amber's
        // launched-for-result path returns it in the "event" extra.
        if (eventJson == null || eventJson.isEmpty()) {
            deliverError("signer returned no event");
            return;
        }
        try {
            JSONObject event = new JSONObject(eventJson);
            deliver(NostrSignerBridge.okObject(event));
        } catch (Exception e) {
            deliverError("signer returned a malformed event");
        }
    }

    /**
     * NIP-07 pubkeys are lowercase hex. Amber may return hex or an
     * {@code npub1…}; normalize both to 64-char hex.
     */
    @Nullable
    private static String normalizePubkey(String raw) {
        String s = raw.trim();
        if (s.startsWith("npub1")) {
            return Bech32.npubToHex(s);
        }
        String lower = s.toLowerCase();
        if (lower.length() == 64 && lower.matches("[0-9a-f]{64}")) {
            return lower;
        }
        return null;
    }

    /** Maps our internal request type to the NIP-55 {@code type} extra value. */
    private static String nip55Type(String internalType) {
        // Same tokens; kept as an explicit mapping so a future rename of the
        // internal type doesn't silently change the wire contract.
        return internalType;
    }

    private void deliver(String envelope) {
        if (mDelivered) {
            return;
        }
        mDelivered = true;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "deliver key=" + mKey + " type=" + (mRequest != null ? mRequest.type : "?"));
        }
        mBridge.deliver(mKey, envelope);
        finish();
    }

    private void deliverError(String message) {
        deliver(NostrSignerBridge.error(message));
    }

    @Override
    protected void onDestroy() {
        // If we're being torn down without having delivered (e.g. the user
        // swiped us away), release the JS Promise so the page doesn't hang and
        // the bridge queue advances.
        if (!mDelivered && mRequest != null && !isChangingConfigurations()) {
            mDelivered = true;
            mBridge.deliver(mKey, NostrSignerBridge.error("user rejected"));
        }
        super.onDestroy();
    }
}
