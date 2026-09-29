package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.R;

/**
 * The caption inks a GRID tile takes while it sits on the mime fallback
 * ground ({@code MimeTypeThumbnail}'s fill path), resolved ONCE per adapter
 * from the {@code mime_fallback_*} colour resources.
 *
 * <p>A grid caption has two grounds: a photo (unknown brightness — white ink
 * over the {@code bottom_scrim}) or the generated placeholder, whose colour is
 * ours. In dark theme both take the same white set, so the flip is a no-op by
 * value; in LIGHT theme the placeholder is a warm cream and the caption must
 * follow it to dark ink (13.5:1) — white would sit at 1.2:1. The resources own
 * the per-theme values; this class only hands them to the three grid adapters
 * (Downloads, Captured, Cloud Backup) so they cannot drift. Never resolve a
 * theme attr for one of these at bind time: the tile's ground does not follow
 * the theme surface, so a surface ink is wrong on it in one theme or the other
 * (the CloudBackup TransferVH lesson).
 */
public final class FallbackInks {

    /** Title line on a placeholder tile. */
    public final int title;
    /** Duration / size / date facts on a placeholder tile. */
    public final int meta;
    /** The bold mime label ({@code MimePrimary}) on a placeholder tile. */
    public final int label;
    /** ERROR / QUEUED / "Finishing…" line on a placeholder tile (coral, darkened
     *  in light theme until it clears 4.5:1). */
    public final int status;
    /** The ⋮ / cancel glyph on a placeholder tile. */
    public final ColorStateList action;

    private FallbackInks(@NonNull Context context) {
        title = ContextCompat.getColor(context, R.color.mime_fallback_title);
        meta = ContextCompat.getColor(context, R.color.mime_fallback_meta);
        label = ContextCompat.getColor(context, R.color.mime_fallback_label);
        status = ContextCompat.getColor(context, R.color.mime_fallback_status);
        action = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.mime_fallback_action));
    }

    @NonNull
    public static FallbackInks resolve(@NonNull Context context) {
        return new FallbackInks(context);
    }
}
