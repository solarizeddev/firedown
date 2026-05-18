package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import com.solarized.firedown.utils.WebUtils;

/**
 * Lean ListAdapter for {@code BookmarksQuickAccessSheet} rows —
 * favicon + title + url + pin badge. Tap dispatches the entity to
 * the host fragment which calls {@code openUri}; selection /
 * action-menu paths are skipped because this surface is read-only.
 */
public class BookmarksQuickAccessAdapter
        extends ListAdapter<WebBookmarkEntity, BookmarksQuickAccessAdapter.RowViewHolder> {

    public interface OnRowClickListener {
        void onRowClick(@NonNull WebBookmarkEntity entity);
    }

    private static final DiffUtil.ItemCallback<WebBookmarkEntity> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull WebBookmarkEntity a, @NonNull WebBookmarkEntity b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull WebBookmarkEntity a, @NonNull WebBookmarkEntity b) {
                    return a.isPinned() == b.isPinned()
                            && safeEq(a.getTitle(), b.getTitle())
                            && safeEq(a.getUrl(), b.getUrl())
                            && safeEq(a.getIcon(), b.getIcon());
                }

                private boolean safeEq(@Nullable String a, @Nullable String b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    private final RequestOptions mRequestOptions = new RequestOptions();
    @Nullable private final OnRowClickListener mListener;

    public BookmarksQuickAccessAdapter(@Nullable OnRowClickListener listener) {
        super(DIFF);
        mListener = listener;
    }

    @NonNull
    @Override
    public RowViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.fragment_bookmark_quick_access_row, parent, false);
        return new RowViewHolder(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {
        holder.bind(getItem(position), mRequestOptions);
    }

    static class RowViewHolder extends RecyclerView.ViewHolder {

        private final View row;
        private final AppCompatImageView icon;
        private final AppCompatImageView pinBadge;
        private final TextView title;
        private final TextView url;
        @Nullable private final OnRowClickListener listener;
        @Nullable private WebBookmarkEntity bound;

        RowViewHolder(@NonNull View itemView, @Nullable OnRowClickListener listener) {
            super(itemView);
            this.row = itemView.findViewById(R.id.item_bookmark_row);
            this.icon = itemView.findViewById(R.id.bookmark_icon);
            this.pinBadge = itemView.findViewById(R.id.bookmark_pin_badge);
            this.title = itemView.findViewById(R.id.bookmark_title);
            this.url = itemView.findViewById(R.id.bookmark_url);
            this.listener = listener;
            this.row.setOnClickListener(v -> {
                if (listener != null && bound != null) listener.onRowClick(bound);
            });
        }

        void bind(@NonNull WebBookmarkEntity entity, @NonNull RequestOptions options) {
            bound = entity;
            title.setText(entity.getTitle());
            url.setText(WebUtils.getDomainName(entity.getUrl()));
            pinBadge.setVisibility(entity.isPinned() ? View.VISIBLE : View.GONE);
            GlideHelper.load(entity.getIcon(), entity.getUrl(), icon, options);
        }
    }
}
