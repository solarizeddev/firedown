package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.RequestOptions;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.WebBookmarkEntity;

/**
 * Favicon-only adapter for the home pinned-bookmarks strip. Each
 * row is a single circular favicon — no title, no url, no badge.
 * The favicons <em>are</em> the content; if the user can't identify
 * a site by its favicon, the bookmarks list (cradle tap) is the
 * right surface, not this.
 *
 * <p>Tap dispatches the entity to a Host callback (HomeFragment),
 * which routes through openUri.</p>
 */
public class PinnedFaviconsAdapter
        extends ListAdapter<WebBookmarkEntity, PinnedFaviconsAdapter.FaviconViewHolder> {

    public interface OnFaviconClickListener {
        void onFaviconClick(@NonNull WebBookmarkEntity entity);
    }

    private static final DiffUtil.ItemCallback<WebBookmarkEntity> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull WebBookmarkEntity a, @NonNull WebBookmarkEntity b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull WebBookmarkEntity a, @NonNull WebBookmarkEntity b) {
                    // Only re-bind when the icon / url change — title /
                    // pin status don't affect a favicon-only row.
                    return safeEq(a.getIcon(), b.getIcon())
                            && safeEq(a.getUrl(), b.getUrl());
                }

                private boolean safeEq(@Nullable String a, @Nullable String b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    private final RequestOptions mRequestOptions = new RequestOptions();
    @Nullable private final OnFaviconClickListener mListener;

    public PinnedFaviconsAdapter(@Nullable OnFaviconClickListener listener) {
        super(DIFF);
        mListener = listener;
    }

    @NonNull
    @Override
    public FaviconViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_pinned_favicon, parent, false);
        return new FaviconViewHolder(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull FaviconViewHolder holder, int position) {
        holder.bind(getItem(position), mRequestOptions);
    }

    static class FaviconViewHolder extends RecyclerView.ViewHolder {

        private final AppCompatImageView favicon;
        @Nullable private final OnFaviconClickListener listener;
        @Nullable private WebBookmarkEntity bound;

        FaviconViewHolder(@NonNull View itemView, @Nullable OnFaviconClickListener listener) {
            super(itemView);
            this.favicon = (AppCompatImageView) itemView;
            this.listener = listener;
            itemView.setOnClickListener(v -> {
                if (listener != null && bound != null) listener.onFaviconClick(bound);
            });
        }

        void bind(@NonNull WebBookmarkEntity entity, @NonNull RequestOptions options) {
            bound = entity;
            GlideHelper.load(entity.getIcon(), entity.getUrl(), favicon, options);
        }
    }
}
