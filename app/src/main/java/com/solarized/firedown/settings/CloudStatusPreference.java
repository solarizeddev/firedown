package com.solarized.firedown.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.FlatPreference;
import com.solarized.firedown.sync.BackupStores;

/**
 * The Backup status card (custom-layout {@link Preference}, styled by
 * {@code preference_cloud_status.xml}), in the Signal "Secure Backups" shape:
 * the words on the start side, a glyph on a disc on the end side, at most one
 * button, inside the card.
 *
 * <ul>
 *   <li><b>Not set up</b> — "Back up your downloads", one line, and the
 *       button the fragment hands in ({@link #setAction}: "Set up backup",
 *       which creates the recovery code and goes straight on to choosing
 *       where to back up).</li>
 *   <li><b>Set up, folder reachable</b> — "On &lt;place&gt;" over the
 *       backed-up size, then the file count (or "Nothing backed up yet" and
 *       the ⋮-menu hint). No button: a backup that needs nothing shows none,
 *       and the "Backup folder" row below carries the change.</li>
 *   <li><b>Set up, folder LOST</b> — the cloud-off glyph and the title in the
 *       error ink, what to do, and the fragment's button ("Choose the folder
 *       again" / "Reconnect").</li>
 * </ul>
 *
 * A running transfer replaces the line under the title with "Transfer in
 * progress…". It replaced a numbered roadmap (① code → ② folder → ③ first
 * backup) with a separate button row under it.
 */
public class CloudStatusPreference extends Preference implements FlatPreference {

    private boolean mSetUp;
    /** Whether the folder answered on the last read. Meaningful only while
     *  set up; defaults to true so a cold bind never flashes the lost state. */
    private boolean mReachable = true;
    private boolean mActive;
    private int mFileCount = -1;
    private long mTotalBytes = -1;
    /** WHERE the backup is — the location label ("Google Drive › Backups"),
     *  or empty when unknown, then the line above the title is hidden. */
    private String mProvider = "";
    /** The kind of place: for a WebDAV server or an S3 bucket the lost
     *  state names it. */
    @NonNull
    private BackupStores.Place mPlace = BackupStores.Place.FOLDER;
    /** The card's button label, 0 = no button. */
    @StringRes
    private int mActionLabel;
    @Nullable
    private Runnable mAction;

    public CloudStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_cloud_status);
        setSelectable(false);
    }

    public void setSetUp(boolean setUp) {
        if (mSetUp != setUp) {
            mSetUp = setUp;
            notifyChanged();
        }
    }

    /** The folder answered (true) or is lost (false) on the latest read. */
    public void setReachable(boolean reachable) {
        if (mReachable != reachable) {
            mReachable = reachable;
            notifyChanged();
        }
    }

    /** A transfer (backup/restore) is running — overrides the line under
     *  the title. */
    public void setActive(boolean active) {
        if (mActive != active) {
            mActive = active;
            notifyChanged();
        }
    }

    /** Backed-up usage from the folder listing ({@code -1} = unknown). */
    public void setUsage(int fileCount, long totalBytes) {
        if (mFileCount != fileCount || mTotalBytes != totalBytes) {
            mFileCount = fileCount;
            mTotalBytes = totalBytes;
            notifyChanged();
        }
    }

    /** The kind of place, which words the lost state. */
    public void setPlace(@NonNull BackupStores.Place place) {
        if (mPlace != place) {
            mPlace = place;
            notifyChanged();
        }
    }

    /** The location the line above the title names ("On Google Drive ›
     *  Backups"). */
    public void setProvider(@Nullable String provider) {
        String next = provider == null ? "" : provider;
        if (!next.equals(mProvider)) {
            mProvider = next;
            notifyChanged();
        }
    }

    /** The card's one button: {@code label} 0 hides it. The runnable is
     *  kept even when the label is unchanged (the fragment rebuilds it). */
    public void setAction(@StringRes int label, @Nullable Runnable action) {
        mAction = action;
        if (mActionLabel != label) {
            mActionLabel = label;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context ctx = getContext();
        ImageView glyph = (ImageView) holder.findViewById(R.id.cb_glyph);
        TextView eyebrow = (TextView) holder.findViewById(R.id.cb_eyebrow);
        TextView title = (TextView) holder.findViewById(R.id.cb_title);
        TextView body = (TextView) holder.findViewById(R.id.cb_body);
        MaterialButton action = (MaterialButton) holder.findViewById(R.id.cb_action);
        if (glyph == null || eyebrow == null || title == null || body == null
                || action == null) {
            return;
        }

        // Every view is set in every branch: the holder recycles.
        int onSurface = MaterialColors.getColor(title,
                com.google.android.material.R.attr.colorOnSurface);
        int error = MaterialColors.getColor(title,
                androidx.appcompat.R.attr.colorError);
        int brand = MaterialColors.getColor(glyph, R.attr.brandInk);
        boolean lost = mSetUp && !mReachable;

        if (!mSetUp || TextUtils.isEmpty(mProvider)) {
            eyebrow.setVisibility(View.GONE);
        } else {
            eyebrow.setText(ctx.getString(R.string.folder_backup_hero_on, mProvider));
            eyebrow.setVisibility(View.VISIBLE);
        }

        glyph.setImageResource(lost ? R.drawable.cloud_off_24 : R.drawable.cloud_upload_24);
        glyph.setImageTintList(ColorStateList.valueOf(lost ? error : brand));
        title.setTextColor(lost ? error : onSurface);
        title.setText(titleText(ctx));
        String line = bodyText(ctx);
        body.setText(line);
        body.setVisibility(TextUtils.isEmpty(line) ? View.GONE : View.VISIBLE);

        if (mActionLabel == 0) {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        } else {
            action.setText(mActionLabel);
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(v -> {
                if (mAction != null) {
                    mAction.run();
                }
            });
        }
    }

    private String titleText(Context ctx) {
        if (!mSetUp) {
            return ctx.getString(R.string.backup_hero_setup_title);
        }
        if (!mReachable) {
            return ctx.getString(lostTitle(mPlace));
        }
        if (mFileCount == 0) {
            return ctx.getString(R.string.cloud_status_empty_title);
        }
        if (mTotalBytes < 0) {
            return ctx.getString(R.string.folder_backup_hero_title);
        }
        return ctx.getString(R.string.backup_hero_size,
                Formatter.formatShortFileSize(ctx, mTotalBytes));
    }

    /**
     * The line under the title. A running transfer wins; then what to do
     * when the folder is lost; then the ⋮-menu hint for an empty backup or
     * "N files · encrypted end-to-end" (the title already IS the size).
     */
    private String bodyText(Context ctx) {
        if (!mSetUp) {
            return ctx.getString(R.string.backup_hero_setup_body);
        }
        if (mActive) {
            return ctx.getString(R.string.settings_cloud_backup_active);
        }
        if (!mReachable) {
            return ctx.getString(switch (mPlace) {
                case WEBDAV -> R.string.webdav_lost_body;
                case S3 -> R.string.s3_lost_body;
                default -> R.string.folder_backup_lost_body;
            });
        }
        if (mFileCount == 0) {
            return ctx.getString(R.string.cloud_status_empty_body);
        }
        if (mFileCount < 0) {
            return "";
        }
        String files = ctx.getResources().getQuantityString(
                R.plurals.settings_cloud_backup_file_count, mFileCount, mFileCount);
        return files + " · " + ctx.getString(R.string.cloud_backup_header_encrypted);
    }

    /** The lost state's title for a kind of place, shared with the Backups
     *  list's header so the two say the same thing. */
    static int lostTitle(@NonNull BackupStores.Place place) {
        return switch (place) {
            case WEBDAV -> R.string.webdav_lost_title;
            case S3 -> R.string.s3_lost_title;
            default -> R.string.folder_backup_lost_title;
        };
    }
}
