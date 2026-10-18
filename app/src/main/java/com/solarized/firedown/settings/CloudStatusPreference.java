package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.FlatPreference;
import com.solarized.firedown.sync.BackupStores;

/**
 * The Backup status hero (custom-layout {@link Preference}, styled by
 * {@code preference_cloud_status.xml}). One card, three states:
 *
 * <ul>
 *   <li><b>Set up, folder reachable</b> — the cloud-upload glyph, "Firedown Backup"
 *       over "On &lt;provider&gt;" (the folder's own name, and whose storage
 *       it sits in — Google Drive, Dropbox, Nextcloud, this phone…), the
 *       backed-up size headline and a caption with the file count. While
 *       nothing is backed up yet the caption is the ⋮-menu hint instead.</li>
 *   <li><b>Set up, folder LOST</b> — the same frame with the cloud-off
 *       glyph (the struck twin of the healthy one), "Can't reach your backup folder", the LAST SEEN totals (so the
 *       user knows what was there), and the body line saying what to do. The
 *       CTA under the hero reads "Choose the folder again" in that state
 *       (SyncSettingsFragment); this card only states the fact.</li>
 *   <li><b>Not set up</b> — the dashed onboarding ROADMAP: ① Create recovery
 *       code → ② Choose backup folder → ③ Back up a finished download from
 *       its ⋮ menu. A done step gets a "✓" prefix and muted ink; the current
 *       step keeps bold full-contrast ink so it reads as "you are here".</li>
 * </ul>
 *
 * A running transfer swaps the caption for the live "Transfer in progress…"
 * line. There is no quota, meter or chip any more: the folder is the user's
 * own storage, so the only facts the card can state are what is in it and
 * whether it answers.
 */
public class CloudStatusPreference extends Preference implements FlatPreference {

    private boolean mSetUp;
    /** Whether the folder answered on the last read. Meaningful only while
     *  set up; defaults to true so a cold bind never flashes the lost state. */
    private boolean mReachable = true;
    private boolean mActive;
    /** Whether a recovery code exists on this device — roadmap step ①. */
    private boolean mHasKey;
    /** Whether a backup folder is chosen — roadmap step ②. */
    private boolean mHasFolder;
    private int mFileCount = -1;
    private long mTotalBytes = -1;
    /** WHERE the backup is — the location label ("Google Drive › Backups":
     *  the provider, then the picked folder's own name; the provider alone
     *  until a read has named the folder), or empty when unknown — then the
     *  subtitle line is hidden. */
    private String mProvider = "";
    /** The kind of place: for a WebDAV server or an S3 bucket the lost
     *  state names it and the remedy is Reconnect, not choosing a folder
     *  again. */
    @NonNull
    private BackupStores.Place mPlace = BackupStores.Place.FOLDER;

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

    /** A recovery code exists — checks off onboarding step ①. */
    public void setHasKey(boolean hasKey) {
        if (mHasKey != hasKey) {
            mHasKey = hasKey;
            notifyChanged();
        }
    }

    /** A backup folder is chosen — checks off onboarding step ②. */
    public void setHasFolder(boolean hasFolder) {
        if (mHasFolder != hasFolder) {
            mHasFolder = hasFolder;
            notifyChanged();
        }
    }

    /** A transfer (backup/restore) is running — overrides the caption line. */
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

    /** The kind of place, which words the lost state: a folder to choose
     *  again, a server or a bucket to reconnect to. */
    public void setPlace(@NonNull BackupStores.Place place) {
        if (mPlace != place) {
            mPlace = place;
            notifyChanged();
        }
    }

    /** The location the subtitle names ("On Google Drive › Backups"). */
    public void setProvider(@Nullable String provider) {
        String next = provider == null ? "" : provider;
        if (!next.equals(mProvider)) {
            mProvider = next;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context ctx = getContext();
        View hero = holder.findViewById(R.id.cb_hero);
        View empty = holder.findViewById(R.id.cb_empty);
        if (hero == null || empty == null) {
            return;
        }
        if (!mSetUp) {
            hero.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            bindOnboardingSteps(ctx, holder);
            return;
        }
        hero.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);

        ImageView glyph = (ImageView) holder.findViewById(R.id.cb_glyph);
        TextView title = (TextView) holder.findViewById(R.id.cb_title);
        TextView subtitle = (TextView) holder.findViewById(R.id.cb_subtitle);
        TextView big = (TextView) holder.findViewById(R.id.cb_big);
        TextView bigUnit = (TextView) holder.findViewById(R.id.cb_big_unit);
        TextView caption = (TextView) holder.findViewById(R.id.cb_caption);
        TextView alert = (TextView) holder.findViewById(R.id.cb_alert);
        if (glyph == null || title == null || subtitle == null || big == null
                || bigUnit == null || caption == null || alert == null) {
            return;
        }

        // Every view is set in BOTH branches — the holder recycles, so a
        // one-sided set would leak the previous state (the one-sided-set rule).
        int onSurface = MaterialColors.getColor(title,
                com.google.android.material.R.attr.colorOnSurface);
        int error = MaterialColors.getColor(title,
                androidx.appcompat.R.attr.colorError);
        if (mReachable) {
            glyph.setImageResource(R.drawable.cloud_upload_24);
            title.setText(R.string.folder_backup_hero_title);
            title.setTextColor(onSurface);
            alert.setVisibility(View.GONE);
        } else {
            glyph.setImageResource(R.drawable.cloud_off_24);
            title.setText(lostTitle(mPlace));
            title.setTextColor(error);
            alert.setText(switch (mPlace) {
                case WEBDAV -> R.string.webdav_lost_body;
                case S3 -> R.string.s3_lost_body;
                default -> R.string.folder_backup_lost_body;
            });
            alert.setVisibility(View.VISIBLE);
        }
        if (TextUtils.isEmpty(mProvider)) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setText(ctx.getString(R.string.folder_backup_hero_on, mProvider));
            subtitle.setVisibility(View.VISIBLE);
        }

        // Headline: how much of the user's stuff is safe (the LAST SEEN figure
        // in the lost state — the files are still in the folder, the device
        // just cannot see it right now).
        big.setText(mTotalBytes >= 0 ? Formatter.formatShortFileSize(ctx, mTotalBytes) : "—");
        bigUnit.setText(R.string.cloud_status_backed_up);
        caption.setText(captionText(ctx));
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

    /**
     * The not-set-up empty state is the onboarding ROADMAP: ① recovery code →
     * ② backup folder → ③ first backup. A DONE step gets a "✓" prefix and
     * muted ink; the next pending step keeps full-contrast ink so it reads as
     * "you are here". ③ is by definition pending while the empty state shows
     * (both ① and ② done IS set up, which flips the card to the hero).
     */
    private void bindOnboardingSteps(Context ctx, PreferenceViewHolder holder) {
        boolean codeDone = mHasKey;
        boolean folderDone = mHasFolder;
        bindStep(holder, R.id.cb_step_code_row, R.id.cb_step_code_mark, R.id.cb_step_code, 1,
                ctx.getString(R.string.settings_sync_create_title), codeDone, !codeDone);
        bindStep(holder, R.id.cb_step_folder_row, R.id.cb_step_folder_mark, R.id.cb_step_folder, 2,
                ctx.getString(R.string.folder_backup_step_folder),
                folderDone, codeDone && !folderDone);
        bindStep(holder, R.id.cb_step_backup_row, R.id.cb_step_backup_mark, R.id.cb_step_backup, 3,
                ctx.getString(R.string.cloud_status_empty_body), false, codeDone && folderDone);
    }

    /**
     * One roadmap row: "✓" muted when done, "N ·" full-contrast bold when
     * it's the current step, "N ·" muted when still ahead. The marker and the
     * text are two columns (the hanging indent), so both take the same ink
     * and weight, and the row carries the line a screen reader speaks.
     */
    private static void bindStep(PreferenceViewHolder holder, int rowId, int markId, int textId,
                                 int number, String text, boolean done, boolean current) {
        View row = holder.findViewById(rowId);
        TextView mark = (TextView) holder.findViewById(markId);
        TextView label = (TextView) holder.findViewById(textId);
        if (row == null || mark == null || label == null) {
            return;
        }
        String marker = done ? "✓" : number + " ·";
        mark.setText(marker);
        label.setText(text);
        row.setContentDescription(marker + " " + text);
        int ink = current
                ? MaterialColors.getColor(label,
                        com.google.android.material.R.attr.colorOnSurface)
                : MaterialColors.getColor(label,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);
        int style = current ? Typeface.BOLD : Typeface.NORMAL;
        for (TextView view : new TextView[] {mark, label}) {
            view.setTextColor(ink);
            view.setTypeface(null, style);
        }
    }

    /**
     * The caption under the headline. A running transfer wins; then the
     * folder-lost "last seen" line; then, with the totals known, the ⋮-menu
     * hint while the folder is empty or "N files · encrypted end-to-end"
     * (the headline above already IS the size, so the caption pairs the count
     * with the trust line instead of repeating it).
     */
    private String captionText(Context ctx) {
        if (mActive) {
            return ctx.getString(R.string.settings_cloud_backup_active);
        }
        if (mFileCount < 0 || mTotalBytes < 0) {
            return ctx.getString(R.string.settings_cloud_backup_usage_unavailable);
        }
        String files = ctx.getResources().getQuantityString(
                R.plurals.settings_cloud_backup_file_count, mFileCount, mFileCount);
        if (!mReachable) {
            return ctx.getString(R.string.folder_backup_lost_seen, files);
        }
        if (mFileCount == 0) {
            return ctx.getString(R.string.cloud_status_empty_body);
        }
        return files + " · " + ctx.getString(R.string.cloud_backup_header_encrypted);
    }
}
