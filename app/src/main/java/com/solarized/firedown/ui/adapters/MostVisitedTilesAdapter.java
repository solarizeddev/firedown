package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AutoCompleteEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Horizontal "top sites" strip for the empty-focus state — its OWN RecyclerView,
 * deliberately separate from the suggestion list so switching empty&harr;typed is a
 * pure visibility flip (no shared {@code ListAdapter} diff, no blink). Each tile
 * is a favicon-in-a-badge + the short site label; a tap opens the entity's URL.
 *
 * <p>The set is small and bounded (capped in {@code AutoCompleteSearch}) and is
 * populated while the strip can be hidden, so a plain {@code notifyDataSetChanged}
 * is fine — there is no recycling pressure and no transition diff.
 */
public class MostVisitedTilesAdapter extends RecyclerView.Adapter<MostVisitedTilesAdapter.TileViewHolder> {

    private final List<AutoCompleteEntity> mItems = new ArrayList<>();
    private final RequestOptions mRequestOptions;
    private final Consumer<String> mOnTileClick;

    public MostVisitedTilesAdapter(Context context, Consumer<String> onTileClick) {
        mOnTileClick = onTileClick;
        int rounded = context.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        mRequestOptions = RequestOptions.bitmapTransform(new RoundedCorners(rounded));
    }

    /** Replace the strip's tiles. Called while the strip may be hidden, so a full
     *  rebind is fine (no visible diff). */
    public void setItems(List<AutoCompleteEntity> items) {
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    public int size() {
        return mItems.size();
    }

    @NonNull
    @Override
    public TileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.fragment_autocomplete_tile, parent, false);
        return new TileViewHolder(view, mOnTileClick);
    }

    @Override
    public void onBindViewHolder(@NonNull TileViewHolder holder, int position) {
        AutoCompleteEntity item = mItems.get(position);
        holder.url = item.getSubText();
        holder.label.setText(siteLabel(item.getSubText()));
        GlideHelper.load(item.getIcon(), item.getSubText(), holder.favicon, mRequestOptions);
    }

    @Override
    public void onViewRecycled(@NonNull TileViewHolder holder) {
        super.onViewRecycled(holder);
        GlideHelper.clearSafe(holder.favicon);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    /** Short site label for a tile — the registrable domain's main label
     *  (second-to-last host part), lowercased, {@code www.} irrelevant. So
     *  {@code m.youtube.com} &rarr; "youtube", {@code watchsomuch.tv} &rarr;
     *  "watchsomuch". Falls back to the bare host, then empty. */
    private static String siteLabel(String url) {
        if (url == null) {
            return "";
        }
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return "";
        }
        if (host == null) {
            return "";
        }
        host = host.toLowerCase(Locale.ROOT);
        String[] parts = host.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return host;
    }

    static class TileViewHolder extends RecyclerView.ViewHolder {
        final AppCompatImageView favicon;
        final TextView label;
        String url;

        TileViewHolder(View view, Consumer<String> onTileClick) {
            super(view);
            favicon = view.findViewById(R.id.tile_favicon);
            favicon.setClipToOutline(true);
            label = view.findViewById(R.id.tile_label);
            view.findViewById(R.id.tile_root).setOnClickListener(v -> {
                if (onTileClick != null && url != null) {
                    onTileClick.accept(url);
                }
            });
        }
    }
}
