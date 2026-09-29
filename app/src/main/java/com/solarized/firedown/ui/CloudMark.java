package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.R;
import com.solarized.firedown.utils.Utils;

import java.util.HashMap;
import java.util.Map;

/**
 * The inline cloud-backup mark — the little filled cloud that leads the
 * Downloads list row's facts line ('{@code [cloud] 11:53 · 27 MB}') and
 * trails the grid caption's meta row ('{@code 3:51 · 40,1 MB [cloud]}').
 *
 * <p>ONE implementation for every surface that renders it (the Downloads
 * list and grid, the Storage review list). It used to be three private
 * pieces inside {@code DownloadItemAdapter}; a second adapter needing the
 * same glyph would have meant copying ~100 lines of measured tuning, which
 * is exactly how {@code compactDuration} once drifted between two surfaces.
 *
 * <p>The tag (glyph + metrics) is built once per SURFACE and cached, keyed
 * by the text size and ink of the TextView it was measured on — the two
 * things it depends on — so a list line and a grid line each get their own
 * correctly-sized, correctly-tinted glyph without the caller having to know
 * which cache it is.
 *
 * <p>FILLED, and at text size that is not a compromise: an outlined icon has
 * a size floor a filled one doesn't. cloud_queue's contour is ~2/24 of its
 * box, so here the stroke lands near 1dp with a counter a few pixels across,
 * and both antialias into a grey smudge — tried on device, rejected. A
 * filled silhouette stays crisp all the way down. Its own earlier blob
 * problem was SIZE (1.15x on a path that fills its box edge to edge is
 * taller than the capitals and wider than any letter), not the fill.
 *
 * <p>Vertical placement centres on the '·' these lines already separate
 * their facts with — measured, not derived. The midpoint of ascent/descent
 * rides ~1dp high because ascent carries the font's accent headroom, and
 * any fixed ratio of the text size is font-dependent where a measurement
 * isn't.
 *
 * <p>Only ONE gutter ever separates the mark from the text (after a leading
 * mark, before a trailing one). It is keyed to the TEXT size rather than
 * the glyph's, so tuning the glyph can't quietly retighten the spacing.
 * 0.55x reads as ~6dp on the 11sp facts line, tuned on device across 0.34x
 * (~3.7dp) and 0.44x (~4.8dp), both of which sat tight against the text.
 */
public final class CloudMark {

    private final Context mContext;
    private final Map<Long, Tag> mTags = new HashMap<>();

    public CloudMark(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * '{@code [cloud] 11:53 · 27 MB}' — the mark as a leading span on a facts
     * line. Returns the text unchanged if the glyph can't be built, so a failed
     * resource lookup degrades instead of handing ImageSpan a null.
     */
    public CharSequence leading(@NonNull TextView view, @NonNull String facts) {
        Tag tag = tagFor(view);
        if (tag == null) {
            return facts;
        }
        SpannableStringBuilder text = new SpannableStringBuilder();
        // One character to hang the span on; its WIDTH comes from the span.
        text.append(' ');
        text.setSpan(new CenteredImageSpan(tag.glyph, tag.baselineOffset, 0, tag.gap),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text.append(facts);
    }

    /**
     * '{@code 3:51 · 40,1 MB [cloud]}' — the mark as the LAST item of a meta
     * row. {@code gapBefore} is false only when the glyph is the row's very
     * first ink (no facts and no mime label before it — the
     * SORT_SIZE-under-a-chip edge, where every text fact drops but a backed-up
     * file must keep its mark). Trailing means the single gutter sits BEFORE
     * the glyph.
     */
    public CharSequence trailing(@NonNull TextView view, @NonNull String facts,
                                 boolean gapBefore) {
        Tag tag = tagFor(view);
        if (tag == null) {
            return facts;
        }
        SpannableStringBuilder text = new SpannableStringBuilder(facts);
        int start = text.length();
        text.append(' ');
        text.setSpan(new CenteredImageSpan(tag.glyph, tag.baselineOffset,
                        gapBefore ? tag.gap : 0, 0),
                start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    @Nullable
    private Tag tagFor(TextView view) {
        long key = ((long) Float.floatToIntBits(view.getTextSize()) << 32)
                | (view.getCurrentTextColor() & 0xFFFFFFFFL);
        Tag tag = mTags.get(key);
        if (tag == null && !mTags.containsKey(key)) {
            tag = build(view);
            mTags.put(key, tag);
        }
        return tag;
    }

    @Nullable
    private Tag build(TextView view) {
        Drawable glyph = Utils.tintDrawableColor(mContext, R.drawable.cloud_24,
                view.getCurrentTextColor());
        if (glyph == null) {
            return null;
        }
        // The cloud stands 16 of its 24 units tall, so 0.9x the text size
        // lands between the x-height and the caps — about the ink of a
        // letter. This is the dial if it reads heavy or slight.
        int size = Math.round(view.getTextSize() * 0.9f);
        glyph.setBounds(0, 0, size, size);
        Paint paint = view.getPaint();
        Rect bounds = new Rect();
        paint.getTextBounds("·", 0, 1, bounds);
        if (bounds.height() <= 0) {
            paint.getTextBounds("x", 0, 1, bounds);
        }
        int baselineOffset = bounds.height() > 0
                ? (bounds.top + bounds.bottom) / 2
                : Math.round(paint.ascent() / 3f);
        int gap = Math.max(1, Math.round(view.getTextSize() * 0.55f));
        return new Tag(glyph, baselineOffset, gap);
    }

    /** One surface's mark: the tinted+sized drawable and the metrics
     *  measured from that surface's own paint. */
    private static final class Tag {
        final Drawable glyph;
        final int baselineOffset;
        final int gap;

        Tag(Drawable glyph, int baselineOffset, int gap) {
            this.glyph = glyph;
            this.baselineOffset = baselineOffset;
            this.gap = gap;
        }
    }

    /**
     * An {@link ImageSpan} that sits on the text's optical centre and keeps its
     * hands off the line's metrics.
     *
     * <p>ALIGN_BASELINE rests the drawable's BOTTOM on the baseline, so a box
     * even slightly taller than the cap height climbs above the text and reads
     * as detached. And {@code DynamicDrawableSpan.getSize} rewrites the line's
     * ascent/descent from the drawable, which would let this glyph set the row
     * height on backed-up rows and the text set it everywhere else.
     */
    private static final class CenteredImageSpan extends ImageSpan {
        private final int mBaselineOffset;
        private final int mGapBefore;
        private final int mGapAfter;

        CenteredImageSpan(Drawable drawable, int baselineOffset, int gapBefore, int gapAfter) {
            super(drawable, ImageSpan.ALIGN_BASELINE);
            mBaselineOffset = baselineOffset;
            mGapBefore = gapBefore;
            mGapAfter = gapAfter;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            // Advance only — fm deliberately untouched.
            return mGapBefore + getDrawable().getBounds().width() + mGapAfter;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            Drawable glyph = getDrawable();
            canvas.save();
            // A leading mark sits flush with the line start (its gap after);
            // a trailing mark carries its gap before, ending flush.
            canvas.translate(x + mGapBefore,
                    y + mBaselineOffset - glyph.getBounds().height() / 2f);
            glyph.draw(canvas);
            canvas.restore();
        }
    }
}
