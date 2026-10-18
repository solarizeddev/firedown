package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.capture.CaptureItem;
import com.solarized.firedown.data.capture.CaptureMark;
import com.solarized.firedown.data.capture.CaptureSections;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.FFmpegTagEntity;
import com.solarized.firedown.ui.CheckerboardDrawable;
import com.solarized.firedown.ui.CloudMark;
import com.solarized.firedown.ui.FallbackInks;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.CaptureItemDiffCallback;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.SelectionStyling;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Captured sheet's adapter: TILES for every capture but an image (the
 * Downloads grid tile, inflated from the same layout file), SQUARES for
 * images under their HEADER, and the FOLD row that holds the earlier pages'
 * captures. The view model builds the item list (see {@link CaptureItem});
 * this class only renders it. Selection is keyed by capture uid, so it
 * survives the list re-sorting under it; while selecting, each "Images"
 * header carries a check that selects or clears its squares at once.
 */
public class BrowserOptionAdapter extends ListAdapter<CaptureItem, RecyclerView.ViewHolder> {

    /** A selection or action-mode change: rebind the selection chrome only. */
    private static final Object PAYLOAD_SELECTION = new Object();

    /** Square text shadow, the grid caption's value (DownloadItemAdapter). */
    private static final int PHOTO_TEXT_SHADOW = 0x80000000;
    /** A square's or tile's progress track over a picture: the caption's
     *  white at 20% (YouTube's track). At 30% it read on-device as a light
     *  grey border across the tile's bottom, with the coral fill a sliver at
     *  a few percent. */
    private static final int PHOTO_TRACK = 0x33FFFFFF;
    /** "1280x720", "24×24": a resolution tag's two numbers. */
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*[x×X]\\s*(\\d+)");
    /** An SVG no larger than this (px, either side) is an icon: drawn inside
     *  capture_icon_inset rather than stretched over the whole square. */
    private static final int ICON_MAX_PX = 96;
    /** The fold's stacked thumbnails: each one this far (dp) to the end of
     *  the one in front of it, as fragment_browser_options_fold lays them. */
    private static final int FOLD_THUMB_DP = 42;
    /** The fold when it can't close (nothing of the page above it): drawn as
     *  a section heading, not a card. A view type of its own, outside
     *  CaptureItem's types. */
    private static final int VIEW_EARLIER_HEADING = 100;
    /** The only tile count at which a page's only video spans the row (a
     *  phone in portrait). With three or more tile columns (landscape, a
     *  tablet) height is the short side and a full-width 16:10 tile would be
     *  taller than the sheet shows, so the video keeps its column. */
    private static final int WIDE_TILE_COLUMNS = 2;
    /** A spanning tile's decode size: GlideHelper decodes every capture
     *  thumbnail at 512x320, a half-width tile's pixels on a phone; across
     *  the row that would show at half its resolution. 16:10, twice over. */
    private static final int WIDE_THUMB_WIDTH = 1024;
    private static final int WIDE_THUMB_HEIGHT = 640;
    private static final int FOLD_THUMB_STEP_DP = 13;
    /** A tile's media kind: its label is the brand ink with this mark
     *  before it (see {@link #bindTile}). */
    private static final int KIND_NONE = 0;
    private static final int KIND_VIDEO = 1;
    private static final int KIND_AUDIO = 2;
    /** The kind mark's size against the label's text size, and its gap. */
    private static final float KIND_MARK_SCALE = 1.1f;
    private static final int KIND_MARK_GAP_DP = 3;
    /** The media label's halo on a picture (see applyTileGround). */
    private static final float MEDIA_SHADOW_RADIUS_DP = 3f;
    private static final int MEDIA_SHADOW = 0xD9000000;

    private final OnItemClickListener mOnItemClickListener;
    private final HashSet<Integer> mSelected = new HashSet<>();
    private final RequestOptions mSquareOptions;
    private final RequestOptions mTileOptions;
    private final CloudMark mBadges;
    private final int mProgressInk;
    private final int mIconInset;
    private final int mProgressTrack;
    private final float mDensity;
    /** This screen's grid lets a page's only video span the row. */
    private final boolean mWideVideo;
    private boolean mActionMode;
    @Nullable private Runnable mOnFoldClick;
    /** Told when a header's check changed the selection (the sheet's
     *  toolbar counts the selected captures). */
    @Nullable private Runnable mOnSelectionChanged;
    /** Resolved on the first bind (needs a themed Context). */
    @Nullable private FallbackInks mFallbackInks;

    public BrowserOptionAdapter(@NonNull Context context, @NonNull OnItemClickListener onItemClickListener) {
        super(new CaptureItemDiffCallback());
        mOnItemClickListener = onItemClickListener;
        int radius = context.getResources().getDimensionPixelOffset(R.dimen.card_radius);
        // A square crops to its own (square) size BEFORE rounding: rounding the
        // decoded bitmap and letting the ImageView crop it would cut the
        // rounded corners off any picture that isn't square.
        mSquareOptions = new RequestOptions().transform(new CenterCrop(), new RoundedCorners(radius));
        // A tile's card clips its own corners and the ImageView crops (the
        // Downloads tile's layout), and GlideHelper crops video frames itself:
        // no transform of ours.
        mTileOptions = new RequestOptions();
        mBadges = new CloudMark(context);
        mIconInset = context.getResources().getDimensionPixelSize(R.dimen.capture_icon_inset);
        mProgressInk = ContextCompat.getColor(context, R.color.progress_indicator);
        mProgressTrack = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(context, android.R.attr.colorPrimary, Color.BLACK), 0x33);
        mDensity = context.getResources().getDisplayMetrics().density;
        mWideVideo = context.getResources().getInteger(R.integer.capture_tile_columns)
                == WIDE_TILE_COLUMNS;
    }

    /** What a tap on the fold row does (the view model opens or closes it). */
    public void setOnFoldClick(@Nullable Runnable onFoldClick) {
        mOnFoldClick = onFoldClick;
    }

    /** Called after a header's check selected or cleared its squares. */
    public void setOnSelectionChanged(@Nullable Runnable onSelectionChanged) {
        mOnSelectionChanged = onSelectionChanged;
    }

    /**
     * The span a position takes on the sheet's grid. The grid's span count is
     * the least common multiple of the square and tile counts (6 for three
     * squares and two tiles on a phone), so both divide it evenly.
     */
    public int spanSize(int position, int spanCount, int squareColumns, int tileColumns) {
        if (position < 0 || position >= getItemCount()) return spanCount;
        CaptureItem item = getItem(position);
        if (item.type == CaptureItem.TYPE_SQUARE) return spanCount / Math.max(1, squareColumns);
        if (item.type == CaptureItem.TYPE_TILE) {
            if (item.onlyVideo && tileColumns == WIDE_TILE_COLUMNS) {
                return spanCount;
            }
            return spanCount / Math.max(1, tileColumns);
        }
        return spanCount;
    }

    /** The tile spans the row on this screen (see {@link #spanSize}). */
    private boolean isWide(@NonNull CaptureItem item) {
        return item.onlyVideo && mWideVideo;
    }

    @Nullable
    public CaptureItem getItemAt(int position) {
        if (position < 0 || position >= getItemCount()) return null;
        return getItem(position);
    }

    /** The capture at a position, or null for a header, the fold or a stale
     *  position. */
    @Nullable
    public BrowserDownloadEntity getEntityAt(int position) {
        CaptureItem item = getItemAt(position);
        return item != null ? item.entity : null;
    }

    /**
     * Glide options for a capture's thumbnail, shared by the bind and the
     * fragment's preloader so the preload-warmed bitmap and the bind-time
     * request resolve to the same cache key. A clone every time: RequestOptions
     * mutates in place, and the shared base would otherwise carry the last
     * capture's metadata into the next request.
     */
    @NonNull
    public RequestOptions optionsFor(@NonNull BrowserDownloadEntity entity, int itemType) {
        String fileUrl = entity.getFileUrl();
        RequestOptions base = itemType == CaptureItem.TYPE_SQUARE ? mSquareOptions : mTileOptions;
        return base.clone()
                .set(GlideRequestOptions.MIMETYPE, resolveMimeType(entity.getMimeType(), fileUrl))
                .set(GlideRequestOptions.FILEPATH, fileUrl)
                .set(GlideRequestOptions.HEADERS, entity.getFileHeaders())
                .set(GlideRequestOptions.KEY, String.valueOf(entity.getUid()));
    }

    @Override
    public int getItemViewType(int position) {
        CaptureItem item = getItem(position);
        if (item.type == CaptureItem.TYPE_FOLD && !item.collapsible) {
            return VIEW_EARLIER_HEADING;
        }
        return item.type;
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof CaptureHolder capture) {
            GlideHelper.clearSafe(capture.image);
        } else if (holder instanceof FoldHolder fold) {
            GlideHelper.clearSafe(fold.thumbs);
        }
    }

    // ── ViewHolder creation ──────────────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return switch (viewType) {
            case CaptureItem.TYPE_SQUARE -> new SquareHolder(
                    inflater.inflate(R.layout.fragment_browser_options_square, parent, false),
                    mOnItemClickListener);
            case CaptureItem.TYPE_HEADER, VIEW_EARLIER_HEADING -> {
                HeaderHolder header = new HeaderHolder(
                        inflater.inflate(R.layout.fragment_browser_options_section_header, parent, false));
                header.itemView.setOnClickListener(v -> toggleSection(header.getBindingAdapterPosition()));
                yield header;
            }
            case CaptureItem.TYPE_FOLD -> new FoldHolder(
                    inflater.inflate(R.layout.fragment_browser_options_fold, parent, false));
            // The Downloads grid tile itself, so the two screens can't drift
            // apart: a change to that layout is a change to both.
            default -> new TileHolder(
                    inflater.inflate(R.layout.fragment_download_item_grid, parent, false),
                    mOnItemClickListener);
        };
    }

    // ── Binding ──────────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || !(holder instanceof CaptureHolder capture)) {
            onBindViewHolder(holder, position);
            return;
        }
        CaptureItem item = getItem(position);
        for (Object payload : payloads) {
            if (payload != CaptureItemDiffCallback.PAYLOAD_MARK && payload != PAYLOAD_SELECTION) {
                onBindViewHolder(holder, position);
                return;
            }
        }
        if (payloads.contains(PAYLOAD_SELECTION)) {
            bindSelection(capture, item);
        }
        if (payloads.contains(CaptureItemDiffCallback.PAYLOAD_MARK)) {
            if (capture instanceof TileHolder tile) {
                bindTileMark(tile, item);
            } else if (capture instanceof SquareHolder square) {
                bindSquareMark(square, item);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CaptureItem item = getItem(position);
        if (holder instanceof TileHolder tile) {
            bindTile(tile, item);
        } else if (holder instanceof SquareHolder square) {
            bindSquare(square, item);
        } else if (holder instanceof HeaderHolder header) {
            bindHeader(header, item, position);
        } else if (holder instanceof FoldHolder fold) {
            bindFold(fold, item);
        }
    }

    /**
     * Diagnostic log of every FULL bind that starts a thumbnail load, and of
     * the load's outcome (debug builds and the diagnostic release build only;
     * {@code adb logcat -s GlideHelper:* CaptureBind:*}). A tile that keeps
     * re-binding refetches its poster each time, so how often a bind happens,
     * and what the last one ended in, is what tells a tile stuck on the glyph
     * from one that only flickers. A payload bind (mark, selection) loads
     * nothing and logs nothing. Literal tag: R8 renames this class.
     */
    private static void logBind(@NonNull String what, @NonNull BrowserDownloadEntity entity) {
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTIC) {
            Log.i("CaptureBind", what + " bind uid=" + entity.getUid()
                    + " mime=" + entity.getMimeType()
                    + " thumb=" + entity.getFileThumbnail()
                    + " url=" + entity.getFileUrl());
        }
    }

    private static void logOutcome(@NonNull String what, int uid, boolean stillBound, boolean fallback) {
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTIC) {
            Log.i("CaptureBind", what + " uid=" + uid + (fallback ? " glyph" : " picture")
                    + (stillBound ? "" : " (holder rebound since, ignored)"));
        }
    }

    // ── Tiles ────────────────────────────────────────────────────────────

    /**
     * A tile, bound the way Downloads binds its grid tile: the title and
     * "VIDEO · 2:01 · CC 1" over the picture on the bottom scrim, the ⋮ in the
     * top corner (here only for a capture with qualities to pick). Two things
     * differ from Downloads, both painted here and not in the shared layout
     * (canvas "Captured Content sheet", boards K and Q):
     *
     * <ul>
     *   <li>A video or audio wears its label in the brand ink with a play or
     *   note mark before it. Without the sheet's old chips nothing else said
     *   which tile is the page's media, and its poster looks like one more
     *   of the images below it. Downloads has no such job (everything there
     *   is a file already) and keeps its plain label.</li>
     *   <li>A running download keeps the picture: a bar along the bottom edge
     *   (the squares' bar) and "Downloading · 21%" in place of the facts.
     *   Downloads swaps the picture for a ring because its thumbnail is made
     *   from the file being written; here the poster is already loaded, and
     *   on the page's only video, which spans the row, a ring on a flat
     *   ground was a large empty field (on-device).</li>
     * </ul>
     *
     * The download mark trails the meta row where Downloads puts its backup
     * mark.
     */
    private void bindTile(@NonNull TileHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        Context context = holder.itemView.getContext();
        if (mFallbackInks == null) {
            mFallbackInks = FallbackInks.resolve(context);
        }

        final int boundUid = entity.getUid();
        holder.boundUid = boundUid;
        holder.hasVariants = entity.getHasVariants();
        holder.more.setIconResource(R.drawable.ic_baseline_more_vert_24);
        holder.more.setContentDescription(context.getString(R.string.capture_show_variants));

        holder.setTextOrHide(holder.fileName, tileTitle(context, entity));
        holder.bottomBlock.setVisibility(View.VISIBLE);

        holder.statusText.setVisibility(View.GONE);
        bindTileFacts(holder, item);
        holder.photo = true;
        applyTileGround(holder);
        RequestOptions options = optionsFor(entity, CaptureItem.TYPE_TILE);
        if (isWide(item)) {
            // Applied after GlideHelper's own size, so it wins.
            options = options.override(WIDE_THUMB_WIDTH, WIDE_THUMB_HEIGHT);
        }
        logBind("tile", entity);
        GlideHelper.load(entity, options, holder.image, fallback -> {
            logOutcome("tile", boundUid, holder.boundUid == boundUid, fallback);
            if (holder.boundUid == boundUid) {
                holder.photo = !fallback;
                applyTileGround(holder);
            }
        });
        bindProgress(holder.progress, item.mark, holder.photo ? PHOTO_TRACK : mProgressTrack);
        bindSelection(holder, item);
    }

    /** A mark change: the facts line (which carries the running download's
     *  percent) and the bar. The picture stays as it is. */
    private void bindTileMark(@NonNull TileHolder holder, @NonNull CaptureItem item) {
        bindTileFacts(holder, item);
        bindProgress(holder.progress, item.mark, holder.photo ? PHOTO_TRACK : mProgressTrack);
    }

    /**
     * "VIDEO" in the mime slot, then
     * " · 2:01:32 · CC 1" and, on earlier pages from several sites, the
     * domain; the download badge trails it all. While the download runs the
     * facts give way to "Downloading · 21%" (or "Finishing…"), the way a
     * Downloads row being backed up reads "Backing up… · 32%".
     */
    private void bindTileFacts(@NonNull TileHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        Context context = holder.itemView.getContext();
        int kind = mediaKind(entity);
        // An audio-flagged mp4 is audio: its label says so (the mime alone
        // would read VIDEO beside the note).
        String label = kind == KIND_AUDIO ? context.getString(R.string.media_type_audio)
                : FileUriHelper.getLongMimeText(context, entity.getMimeType());
        holder.setTextOrHide(holder.mimeText, label);
        bindKindMark(holder, kind);

        boolean downloading = item.mark != null && item.mark.state == CaptureMark.PROGRESS;
        String facts = downloading ? statusText(context, item.mark) : tileFacts(context, item, entity);
        boolean mimeShown = holder.mimeText.getVisibility() == View.VISIBLE;
        String shown = TextUtils.isEmpty(facts) ? "" : (mimeShown ? " · " + facts : facts);
        int badge = badgeFor(item.mark);
        if (badge != 0) {
            holder.meta.setText(mBadges.trailingBadge(holder.meta, shown, badge,
                    !shown.isEmpty() || mimeShown));
            holder.meta.setContentDescription(joinFacts(facts, statusText(context, item.mark)));
            holder.meta.setVisibility(View.VISIBLE);
        } else {
            holder.meta.setText(shown);
            holder.meta.setContentDescription(null);
            holder.meta.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    @NonNull
    private static String tileFacts(@NonNull Context context, @NonNull CaptureItem item,
                                     @NonNull BrowserDownloadEntity entity) {
        String mime = entity.getMimeType();
        StringBuilder facts = new StringBuilder();
        if (FileUriHelper.isSubtitle(mime)) {
            // The title is the track's language when the parser named one, so
            // the meta row carries the name instead: two "English" tracks of
            // two videos stay told apart.
            if (!TextUtils.isEmpty(languageFact(entity))) {
                appendFact(facts, entity.getFileName());
            } else {
                appendFact(facts, primaryFact(entity));
            }
        } else if (CaptureSections.isVideo(entity) || CaptureSections.isAudioCapture(entity)) {
            appendFact(facts, durationFact(entity));
        } else {
            appendFact(facts, primaryFact(entity));
        }
        if (item.captionCount > 0) {
            appendFact(facts, context.getString(R.string.caption_count_badge, item.captionCount));
        }
        if (item.showDomain) {
            appendFact(facts, domainOf(entity));
        }
        return facts.toString();
    }

    /**
     * The tile's title: a subtitle track's language when the parser named one;
     * for a video or audio whose name is a filename ("src", "master.m3u8", a
     * hash — what an embedded player's capture is called when the page names
     * nothing better), "Video on news.example"; otherwise the name.
     */
    @Nullable
    private static String tileTitle(@NonNull Context context, @NonNull BrowserDownloadEntity entity) {
        if (FileUriHelper.isSubtitle(entity.getMimeType())) {
            String language = languageFact(entity);
            if (!TextUtils.isEmpty(language)) return language;
        }
        String name = entity.getFileName();
        boolean video = CaptureSections.isVideo(entity);
        if ((video || CaptureSections.isAudioCapture(entity)) && CaptureSections.isFilenameLike(name)) {
            String domain = domainOf(entity);
            if (!TextUtils.isEmpty(domain)) {
                return context.getString(video ? R.string.capture_video_on_site
                        : R.string.capture_audio_on_site, domain);
            }
        }
        return name;
    }

    /**
     * Downloads' ground rule (applyGridTileGround): a real picture gets the
     * scrim and the layout's white inks with their shadow; the mime
     * placeholder and the in-progress ground get no scrim and FallbackInks.
     */
    private void applyTileGround(@NonNull TileHolder holder) {
        FallbackInks inks = mFallbackInks;
        if (inks == null) return;
        boolean photo = holder.photo;
        if (photo) {
            holder.bottomBlock.setBackgroundResource(R.drawable.bottom_scrim);
        } else {
            holder.bottomBlock.setBackground(null);
        }
        float radius = photo ? 2f : 0f;
        int shadow = photo ? PHOTO_TEXT_SHADOW : Color.TRANSPARENT;
        holder.fileName.setShadowLayer(radius, 0f, 1f, shadow);
        holder.fileName.setTextColor(photo ? holder.photoTitleInk : inks.title);
        if (photo && holder.kind != KIND_NONE) {
            // A darker, wider halo than the white label's: coral has far less
            // contrast than white where a bright poster shows through the
            // scrim's lighter middle.
            holder.mimeText.setShadowLayer(MEDIA_SHADOW_RADIUS_DP * mDensity, 0f, mDensity, MEDIA_SHADOW);
        } else {
            holder.mimeText.setShadowLayer(radius, 0f, 1f, shadow);
        }
        int label = labelInk(holder);
        holder.mimeText.setTextColor(label);
        TextViewCompat.setCompoundDrawableTintList(holder.mimeText, ColorStateList.valueOf(label));
        holder.meta.setShadowLayer(radius, 0f, 1f, shadow);
        holder.meta.setTextColor(photo ? holder.photoMetaInk : inks.meta);
        holder.statusText.setTextColor(inks.status);
        holder.more.setIconTint(photo ? ColorStateList.valueOf(Color.WHITE) : inks.action);
        if (holder.progress.getVisibility() == View.VISIBLE) {
            holder.progress.setTrackColor(photo ? PHOTO_TRACK : mProgressTrack);
        }
    }

    /**
     * The mime label's ink. A video or audio's is the brand ink on a picture
     * (reads well on real thumbnails; over a pure-white frame the scrim
     * behind the label is only ~60% black, about 2:1 for coral against
     * white's 5.8:1 — hence the halo in applyTileGround) and the
     * placeholder's status ink without one (the brand coral is 2.2:1 on the
     * light theme's pale wash; FallbackInks' status red is measured against
     * it). Any other type keeps the plain label inks.
     */
    private int labelInk(@NonNull TileHolder holder) {
        FallbackInks inks = mFallbackInks;
        boolean media = holder.kind != KIND_NONE;
        if (holder.photo) {
            return media ? holder.mediaInk : holder.photoLabelInk;
        }
        if (inks == null) {
            return holder.photoLabelInk;
        }
        return media ? inks.status : inks.label;
    }

    /** VIDEO, AUDIO, or anything else (no mark, plain label). */
    private static int mediaKind(@NonNull BrowserDownloadEntity entity) {
        if (CaptureSections.isVideo(entity)) return KIND_VIDEO;
        if (CaptureSections.isAudioCapture(entity)) return KIND_AUDIO;
        return KIND_NONE;
    }

    /**
     * The play or note mark before a media label, sized to the label's text
     * and tinted with its ink (applyTileGround). Built only when the tile's
     * kind changes: a mark payload rebinds the facts on every progress tick.
     */
    private void bindKindMark(@NonNull TileHolder holder, int kind) {
        holder.kind = kind;
        if (holder.markKind == kind) return;
        holder.markKind = kind;
        if (kind == KIND_NONE) {
            holder.mimeText.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        Drawable mark = AppCompatResources.getDrawable(holder.itemView.getContext(),
                kind == KIND_VIDEO ? R.drawable.capture_kind_video : R.drawable.capture_kind_audio);
        if (mark == null) return;
        mark = mark.mutate();
        int size = Math.round(holder.mimeText.getTextSize() * KIND_MARK_SCALE);
        mark.setBounds(0, 0, size, size);
        holder.mimeText.setCompoundDrawablesRelative(mark, null, null, null);
        holder.mimeText.setCompoundDrawablePadding(Math.round(KIND_MARK_GAP_DP * mDensity));
        TextViewCompat.setCompoundDrawableTintList(holder.mimeText,
                ColorStateList.valueOf(labelInk(holder)));
    }

    // ── Squares ──────────────────────────────────────────────────────────

    private void bindSquare(@NonNull SquareHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        Context context = holder.itemView.getContext();
        if (mFallbackInks == null) {
            mFallbackInks = FallbackInks.resolve(context);
        }

        // Dimensions, led by GIF / SVG: under the "Images" header a plain
        // image needs no label, but a GIF among them is worth knowing, and
        // the resolution is the one fact a picture's thumbnail doesn't show.
        String mime = entity.getMimeType();
        String label = (FileUriHelper.isGIF(mime) || FileUriHelper.isSVG(mime))
                ? FileUriHelper.getLongMimeText(context, mime).toUpperCase(Locale.getDefault())
                : null;
        String caption = joinFacts(label, primaryFact(entity));
        holder.setTextOrHide(holder.caption, caption);
        holder.item.setContentDescription(entity.getFileName());

        // A small SVG is an icon: drawn at its own scale inside an inset
        // instead of stretched over the square (a 24px player glyph filled the
        // whole tile as a black blob). An SVG with no known size is treated as
        // one too — on a web page an SVG is nearly always an icon or a logo.
        boolean icon = FileUriHelper.isSVG(mime) && !isLargerThan(primaryFact(entity), ICON_MAX_PX);
        holder.image.setScaleType(icon ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        int inset = icon ? mIconInset : 0;
        holder.image.setPadding(inset, inset, inset, inset);

        final int boundUid = entity.getUid();
        holder.boundUid = boundUid;
        holder.photo = true;
        // The checkerboard goes on only once a real picture is there, so a
        // square still loading shows the card's plain ground, not a flash of
        // checks; the mime placeholder is opaque and needs none.
        holder.image.setBackground(null);
        applySquareGround(holder);
        GlideHelper.load(entity, optionsFor(entity, CaptureItem.TYPE_SQUARE), holder.image, fallback -> {
            if (holder.boundUid == boundUid) {
                holder.photo = !fallback;
                holder.image.setBackground(fallback ? null : holder.checker);
                if (fallback) {
                    // The mime placeholder fills the square like any other's.
                    holder.image.setPadding(0, 0, 0, 0);
                }
                applySquareGround(holder);
            }
        });
        bindSelection(holder, item);
        bindSquareMark(holder, item);
    }

    private void bindSquareMark(@NonNull SquareHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        CaptureMark mark = item.mark;
        int badge = badgeFor(mark);
        if (badge != 0) {
            holder.badge.setImageResource(badge);
            holder.badge.setContentDescription(statusText(context, mark));
            holder.badge.setVisibility(View.VISIBLE);
        } else {
            holder.badge.setImageDrawable(null);
            holder.badge.setContentDescription(null);
            holder.badge.setVisibility(View.GONE);
        }
        bindProgress(holder.progress, mark, holder.photo ? PHOTO_TRACK : mProgressTrack);
        holder.bottomBlock.setVisibility(
                holder.caption.getVisibility() == View.VISIBLE || badge != 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * The square's caption ink follows what the slot ends up showing: white
     * with a shadow over the scrim on a picture, FallbackInks on the mime
     * placeholder (dark on the light theme's wash), with no scrim there — a
     * gradient over a flat colour is a smudge. The badge glyph carries its own
     * baked shadow, which reads on both. Same rule as the tiles'.
     */
    private void applySquareGround(@NonNull SquareHolder holder) {
        if (mFallbackInks == null) return;
        if (holder.photo) {
            holder.bottomBlock.setBackgroundResource(R.drawable.bottom_scrim);
            holder.caption.setTextColor(Color.WHITE);
            holder.caption.setShadowLayer(2f, 0f, 1f, PHOTO_TEXT_SHADOW);
        } else {
            holder.bottomBlock.setBackground(null);
            holder.caption.setTextColor(mFallbackInks.label);
            holder.caption.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
        if (holder.progress.getVisibility() == View.VISIBLE) {
            holder.progress.setTrackColor(holder.photo ? PHOTO_TRACK : mProgressTrack);
        }
    }

    private void bindProgress(@NonNull LinearProgressIndicator progress, @Nullable CaptureMark mark,
                              int track) {
        if (mark == null || mark.state != CaptureMark.PROGRESS) {
            progress.setVisibility(View.GONE);
            return;
        }
        progress.setIndicatorColor(mProgressInk);
        progress.setTrackColor(track);
        // Only on a real change: re-setting it restarts the indeterminate
        // animation, and a mark payload arrives on every progress tick.
        if (progress.isIndeterminate() != mark.indeterminate) {
            progress.setIndeterminate(mark.indeterminate);
        }
        if (!mark.indeterminate) {
            progress.setProgressCompat(mark.percent, progress.getVisibility() == View.VISIBLE);
        }
        progress.setVisibility(View.VISIBLE);
    }

    // ── The fold ─────────────────────────────────────────────────────────

    /**
     * "Images / 9 files" over a group's squares, or the fold that can't close
     * as a heading of the same shape: "Earlier in this tab / 30 files ·
     * twitch.tv, higgsfield.ai". That is the fold with nothing of the page
     * above it (no page, or nothing caught yet): the earlier captures are
     * all the list holds and always shown, so a card with a chevron there
     * would offer a toggle with nothing to give back. Both titles are set
     * here, never left to the layout's default text.
     *
     * <p>While selecting, an "Images" header shows the selection check at
     * its end and a tap selects every square under it, or clears them when
     * all are already selected (Google Photos' date-header check). It is
     * what the sheet's Images chip + Select all used to do, without a chip:
     * the toolbar's Select all takes the whole list, this takes one section.
     */
    private void bindHeader(@NonNull HeaderHolder holder, @NonNull CaptureItem item, int position) {
        Context context = holder.itemView.getContext();
        if (item.type == CaptureItem.TYPE_FOLD) {
            holder.title.setText(R.string.capture_earlier_in_tab);
            holder.count.setText(foldSubtitle(context, item));
        } else {
            holder.title.setText(R.string.sort_by_type_images);
            holder.count.setText(filesLabel(context, item.count));
        }
        List<Integer> squares = item.type == CaptureItem.TYPE_HEADER && mActionMode
                ? CaptureSections.squaresUnder(getCurrentList(), position)
                : List.of();
        boolean selectable = !squares.isEmpty();
        boolean all = selectable && allSelected(squares);
        holder.check.setVisibility(selectable ? View.VISIBLE : View.GONE);
        holder.check.setImageDrawable(!selectable ? null
                : all ? SelectionStyling.checkedDrawable(context)
                : SelectionStyling.uncheckedDrawable(context));
        holder.itemView.setClickable(selectable);
        // A heading to the screen reader at rest; while selecting, also a
        // control whose action says what a tap does.
        if (selectable) {
            ViewCompat.replaceAccessibilityAction(holder.itemView,
                    AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(all ? R.string.select_all_not : R.string.select_all), null);
        } else {
            ViewCompat.removeAccessibilityAction(holder.itemView,
                    AccessibilityActionCompat.ACTION_CLICK.getId());
        }
    }

    /** "29 files · youtube.com, firedown.app". */
    @NonNull
    private static String foldSubtitle(@NonNull Context context, @NonNull CaptureItem item) {
        String subtitle = filesLabel(context, item.count);
        if (!item.domains.isEmpty()) {
            subtitle = subtitle + " · " + TextUtils.join(", ", item.domains);
        }
        return subtitle;
    }

    /**
     * "Earlier in this tab / 29 files · youtube.com, firedown.app", three
     * stacked thumbnails of what is inside, and the chevron, turned while it
     * is open. The tap and the accessibility action both go to the view
     * model, which rebuilds the list with the earlier captures in or out.
     * Only a fold that can close comes here; one that can't is a heading
     * ({@link #bindHeader}).
     */
    private void bindFold(@NonNull FoldHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        holder.subtitle.setText(foldSubtitle(context, item));
        holder.chevron.setRotation(item.open ? 180f : 0f);

        int shown = 0;
        for (int i = 0; i < holder.thumbs.length; i++) {
            AppCompatImageView thumb = holder.thumbs[i];
            if (i < item.preview.size()) {
                BrowserDownloadEntity entity = item.preview.get(i);
                thumb.setVisibility(View.VISIBLE);
                logBind("fold preview", entity);
                GlideHelper.load(entity, optionsFor(entity, CaptureItem.TYPE_TILE), thumb);
                shown++;
            } else {
                GlideHelper.clearSafe(thumb);
                thumb.setVisibility(View.GONE);
            }
        }
        ViewGroup.LayoutParams params = holder.stack.getLayoutParams();
        int width = shown == 0 ? 0
                : Math.round((FOLD_THUMB_DP + FOLD_THUMB_STEP_DP * (shown - 1)) * mDensity);
        if (params.width != width) {
            params.width = width;
            holder.stack.setLayoutParams(params);
        }
        holder.stack.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);

        // Announced as an expand / collapse control in the screen reader's own
        // words: the platform actions carry their labels.
        ViewCompat.removeAccessibilityAction(holder.card,
                AccessibilityActionCompat.ACTION_EXPAND.getId());
        ViewCompat.removeAccessibilityAction(holder.card,
                AccessibilityActionCompat.ACTION_COLLAPSE.getId());
        ViewCompat.replaceAccessibilityAction(holder.card,
                item.open ? AccessibilityActionCompat.ACTION_COLLAPSE
                        : AccessibilityActionCompat.ACTION_EXPAND,
                null, (view, arguments) -> {
                    toggleFold();
                    return true;
                });
    }

    /** Inert while selecting: the sheet holds the list still in action mode
     *  (its observer drops updates so positions stay put under the
     *  selection), so a tap would change state the user can't see. Open the
     *  fold first to select earlier captures. */
    private void toggleFold() {
        if (mOnFoldClick != null && !mActionMode) {
            mOnFoldClick.run();
        }
    }

    // ── Selection ────────────────────────────────────────────────────────

    /**
     * Selection chrome, matching the Downloads grid: the 2dp stroke on the
     * card plus the corner check, a tile's ⋮ hidden while selecting. Ink is
     * brandInk (SelectionStyling), resolved off the holder's own context: in
     * a private tab the sheet inflates under the incognito overlay, whose
     * brandInk is its own.
     */
    private void bindSelection(@NonNull CaptureHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        boolean selected = mActionMode && mSelected.contains(item.uid());
        holder.checkedView.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
        holder.checkedView.setImageDrawable(!mActionMode ? null
                : selected ? SelectionStyling.checkedDrawable(context)
                : SelectionStyling.uncheckedDrawable(context));
        holder.item.setStrokeColor(selected ? SelectionStyling.selectionInk(context) : Color.TRANSPARENT);
        if (holder instanceof TileHolder tile) {
            // The sheet's own card tone (the layout's is Downloads' page-on-
            // surface one, which would vanish into the sheet while loading).
            int ground = com.google.android.material.R.attr.colorSurfaceContainerLow;
            tile.item.setCardBackgroundColor(selected
                    ? SelectionStyling.selectedCardWashOver(context, ground)
                    : MaterialColors.getColor(context, ground, Color.TRANSPARENT));
            tile.more.setVisibility(mActionMode ? View.INVISIBLE
                    : (tile.hasVariants ? View.VISIBLE : View.GONE));
        }
    }

    // ── Facts ────────────────────────────────────────────────────────────

    /**
     * The capture's leading fact: video quality ("1080p"), image dimensions,
     * or a subtitle's language — whichever tag the parser set, the last one
     * winning as it always did; an untyped tag only when none of those exist.
     */
    @Nullable
    private static String primaryFact(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) return null;
        String primary = null;
        String untyped = null;
        for (FFmpegTagEntity tag : tags) {
            if (tag == null || TextUtils.isEmpty(tag.getText())) continue;
            switch (tag.getType()) {
                case FFmpegTagEntity.TYPE_QUALITY, FFmpegTagEntity.TYPE_RESOLUTION,
                     FFmpegTagEntity.TYPE_LANGUAGE -> primary = tag.getText();
                case FFmpegTagEntity.TYPE_DURATION -> { }
                default -> {
                    if (untyped == null) untyped = tag.getText();
                }
            }
        }
        return primary != null ? primary : untyped;
    }

    /** A subtitle track's language tag ("English (auto)"), or null. */
    @Nullable
    private static String languageFact(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) return null;
        for (FFmpegTagEntity tag : tags) {
            if (tag != null && tag.getType() == FFmpegTagEntity.TYPE_LANGUAGE
                    && !TextUtils.isEmpty(tag.getText())) {
                return tag.getText();
            }
        }
        return null;
    }

    /** Whether a "WxH" fact names a side larger than {@code maxPx}; false
     *  when there is no size to read. */
    private static boolean isLargerThan(@Nullable String size, int maxPx) {
        if (TextUtils.isEmpty(size)) return false;
        Matcher m = SIZE.matcher(size);
        if (!m.find()) return false;
        try {
            return Integer.parseInt(m.group(1)) > maxPx || Integer.parseInt(m.group(2)) > maxPx;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Duration, trimmed at render exactly as Downloads does
     *  (DateUtils.compactDuration: "00:00:11" → "0:11"). */
    @Nullable
    private static String durationFact(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) return null;
        for (FFmpegTagEntity tag : tags) {
            if (tag != null && tag.getType() == FFmpegTagEntity.TYPE_DURATION
                    && !TextUtils.isEmpty(tag.getText())) {
                return DateUtils.compactDuration(tag.getText());
            }
        }
        return null;
    }

    @Nullable
    private static String domainOf(@NonNull BrowserDownloadEntity entity) {
        String origin = entity.getFileOrigin();
        return WebUtils.getDomainName(TextUtils.isEmpty(origin) ? entity.getFileUrl() : origin);
    }

    /** The download mark's badge: saved, queued or failed. A running
     *  download has none (its bar says it). */
    @DrawableRes
    private static int badgeFor(@Nullable CaptureMark mark) {
        if (mark == null) return 0;
        return switch (mark.state) {
            case CaptureMark.SAVED -> R.drawable.capture_badge_saved;
            case CaptureMark.PENDING -> R.drawable.capture_badge_pending;
            case CaptureMark.FAILED -> R.drawable.capture_badge_failed;
            default -> 0;
        };
    }

    @Nullable
    private static String statusText(@NonNull Context context, @Nullable CaptureMark mark) {
        if (mark == null) return null;
        return switch (mark.state) {
            case CaptureMark.PROGRESS -> mark.finishing
                    ? context.getString(R.string.download_finishing)
                    : mark.indeterminate
                            ? context.getString(R.string.downloading)
                            : context.getString(R.string.capture_status_downloading, mark.percent);
            case CaptureMark.PENDING -> context.getString(R.string.download_queued);
            case CaptureMark.SAVED -> context.getString(R.string.capture_status_saved);
            default -> context.getString(R.string.capture_status_failed);
        };
    }

    /** "9 files": the Downloads section header's own count. */
    @NonNull
    private static String filesLabel(@NonNull Context context, int count) {
        return context.getResources().getQuantityString(R.plurals.downloads_group_files, count, count);
    }

    private static void appendFact(@NonNull StringBuilder text, @Nullable String fact) {
        if (TextUtils.isEmpty(fact)) return;
        if (text.length() > 0) text.append(" · ");
        text.append(fact);
    }

    @Nullable
    private static String joinFacts(@Nullable String a, @Nullable String b) {
        if (TextUtils.isEmpty(a)) return b;
        if (TextUtils.isEmpty(b)) return a;
        return a + " · " + b;
    }


    // ── Selection (keyed by UID) ─────────────────────────────────────────

    public void toggleSelected(int position) {
        CaptureItem item = getItemAt(position);
        if (item == null || !item.isCapture()) return;
        int uid = item.uid();
        if (!mSelected.remove(uid)) {
            mSelected.add(uid);
        }
        notifyItemChanged(position, PAYLOAD_SELECTION);
        // A square can complete or break its section: its header's check.
        int header = CaptureSections.headerAbove(getCurrentList(), position);
        if (header >= 0) {
            notifyItemChanged(header, PAYLOAD_SELECTION);
        }
    }

    /**
     * An "Images" header's check: select every square under it, or clear
     * them all when every one is selected already. Inert outside selection
     * (the check isn't shown then) and on the fold's heading.
     */
    private void toggleSection(int header) {
        if (!mActionMode || header == RecyclerView.NO_POSITION) return;
        List<Integer> squares = CaptureSections.squaresUnder(getCurrentList(), header);
        if (squares.isEmpty()) return;
        boolean clear = allSelected(squares);
        for (int position : squares) {
            int uid = getItem(position).uid();
            if (clear) {
                mSelected.remove(uid);
            } else {
                mSelected.add(uid);
            }
        }
        notifyItemRangeChanged(header, squares.size() + 1, PAYLOAD_SELECTION);
        if (mOnSelectionChanged != null) {
            mOnSelectionChanged.run();
        }
    }

    private boolean allSelected(@NonNull List<Integer> positions) {
        for (int position : positions) {
            if (!mSelected.contains(getItem(position).uid())) return false;
        }
        return true;
    }

    public int getSelectedSize() {
        return mSelected.size();
    }

    public HashSet<Integer> getSelected() {
        return mSelected;
    }

    public void clearSelection() {
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    /** Selects every capture in the list: with the fold closed, that is the
     *  current page's captures only — what the user can see. */
    public void selectAll() {
        for (int i = 0; i < getItemCount(); i++) {
            CaptureItem item = getItem(i);
            if (item.isCapture()) {
                mSelected.add(item.uid());
            }
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    public void setActionMode(boolean value) {
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Falls back to extension-based detection when the entity's stored mime
     * type is missing or generic.
     */
    public static String resolveMimeType(@Nullable String mimeType, String fileUrl) {
        if (TextUtils.isEmpty(mimeType) || FileUriHelper.isMimeTypeUnknown(mimeType)) {
            return FileUriHelper.getMimeTypeFromFile(fileUrl);
        }
        return mimeType;
    }


    // ── ViewHolders ──────────────────────────────────────────────────────

    /** A tile or a square: a capture, clickable, selectable. */
    abstract static class CaptureHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener {

        final OnItemClickListener listener;
        final MaterialCardView item;
        final AppCompatImageView image;
        final AppCompatImageView checkedView;
        /** The uid this holder binds now — a Glide callback lands
         *  asynchronously and must not repaint a recycled holder. */
        int boundUid = -1;

        CaptureHolder(@NonNull View view, OnItemClickListener listener, int checkId) {
            super(view);
            this.listener = listener;
            item = view.findViewById(R.id.item);
            image = view.findViewById(R.id.image);
            checkedView = view.findViewById(checkId);
            item.setOnClickListener(this);
            item.setOnLongClickListener(this);
        }

        void setTextOrHide(@Nullable TextView tv, @Nullable String text) {
            if (tv == null) return;
            if (TextUtils.isEmpty(text)) {
                tv.setVisibility(View.GONE);
                return;
            }
            tv.setVisibility(View.VISIBLE);
            tv.setText(text);
        }

        @Override
        public void onClick(View v) {
            int position = getBindingAdapterPosition();
            if (listener != null && position != RecyclerView.NO_POSITION) {
                listener.onItemClick(position, v.getId());
            }
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getBindingAdapterPosition();
            if (listener != null && position != RecyclerView.NO_POSITION) {
                listener.onLongClick(position, v.getId());
                return true;
            }
            return false;
        }
    }

    /** The Downloads grid tile (fragment_download_item_grid.xml), plus the
     *  download bar the sheet adds to it. */
    static final class TileHolder extends CaptureHolder {
        final LinearProgressIndicator progress;
        final View bottomBlock;
        final TextView fileName;
        final TextView mimeText;
        final TextView meta;
        final TextView statusText;
        final MaterialButton more;
        /** The layout's own photo inks, restored on a holder that last showed
         *  a placeholder: read once here, never written down twice. */
        final int photoTitleInk;
        final int photoLabelInk;
        final int photoMetaInk;
        /** A video or audio label's ink on a picture: brandInk, read off the
         *  holder's own context (a private tab's sheet has its overlay). */
        final int mediaInk;
        /** The picture shows a real frame or poster (vs the mime placeholder). */
        boolean photo = true;
        boolean hasVariants;
        /** The bound capture's media kind, and the kind whose mark the label
         *  carries now (-1: none built yet). */
        int kind = KIND_NONE;
        int markKind = -1;

        TileHolder(@NonNull View view, OnItemClickListener listener) {
            super(view, listener, R.id.item_download_selected);
            bottomBlock = view.findViewById(R.id.bottom_block);
            fileName = view.findViewById(R.id.file_name);
            mimeText = view.findViewById(R.id.mime_text);
            meta = view.findViewById(R.id.mime_duration);
            statusText = view.findViewById(R.id.status_text);
            more = view.findViewById(R.id.item_download_action);
            photoTitleInk = fileName.getCurrentTextColor();
            photoLabelInk = mimeText.getCurrentTextColor();
            photoMetaInk = meta.getCurrentTextColor();
            mediaInk = MaterialColors.getColor(view, R.attr.brandInk);
            progress = addProgressBar((ConstraintLayout) bottomBlock.getParent());
            image.setClipToOutline(true);
            more.setOnClickListener(this);
            checkedView.setOnClickListener(this);
            Utils.expandTouchArea(more);
        }

        /** The bar along the tile's bottom edge, over the caption's scrim.
         *  Added here, not in the shared layout, so the Downloads grid carries
         *  no view it never shows; the card clips it to its corners. */
        @NonNull
        private static LinearProgressIndicator addProgressBar(@NonNull ConstraintLayout content) {
            LinearProgressIndicator bar = (LinearProgressIndicator) LayoutInflater.from(content.getContext())
                    .inflate(R.layout.capture_tile_progress, content, false);
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) bar.getLayoutParams();
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            content.addView(bar, params);
            return bar;
        }

        @Override
        public void onClick(View v) {
            // The layout's ids are Downloads': the ⋮ reports as the sheet's
            // own action-slot id, the check as a tap on the tile.
            int position = getBindingAdapterPosition();
            if (listener == null || position == RecyclerView.NO_POSITION) return;
            if (v == more) {
                listener.onItemClick(position, R.id.item_download_more);
            } else {
                listener.onItemClick(position, R.id.item);
            }
        }
    }

    static final class SquareHolder extends CaptureHolder {
        final View bottomBlock;
        final TextView caption;
        final AppCompatImageView badge;
        final LinearProgressIndicator progress;
        /** Shown behind a loaded picture, through its transparent pixels. */
        final CheckerboardDrawable checker;
        /** The slot shows a real picture (vs the mime placeholder). */
        boolean photo = true;

        SquareHolder(@NonNull View view, OnItemClickListener listener) {
            super(view, listener, R.id.item_download_more_checked);
            checker = new CheckerboardDrawable(view.getContext());
            bottomBlock = view.findViewById(R.id.bottom_block);
            caption = view.findViewById(R.id.square_caption);
            badge = view.findViewById(R.id.square_badge);
            progress = view.findViewById(R.id.square_progress);
        }
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView count;
        final ImageView check;

        HeaderHolder(@NonNull View view) {
            super(view);
            title = view.findViewById(R.id.section_title);
            count = view.findViewById(R.id.section_count);
            check = view.findViewById(R.id.section_check);
            ViewCompat.setAccessibilityHeading(view, true);
        }
    }

    final class FoldHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final View stack;
        final AppCompatImageView[] thumbs;
        final TextView subtitle;
        final ImageView chevron;

        FoldHolder(@NonNull View view) {
            super(view);
            card = view.findViewById(R.id.fold);
            stack = view.findViewById(R.id.fold_stack);
            thumbs = new AppCompatImageView[] {
                    view.findViewById(R.id.fold_thumb_1),
                    view.findViewById(R.id.fold_thumb_2),
                    view.findViewById(R.id.fold_thumb_3)
            };
            subtitle = view.findViewById(R.id.fold_subtitle);
            chevron = view.findViewById(R.id.fold_chevron);
            card.setOnClickListener(v -> toggleFold());
        }
    }
}
