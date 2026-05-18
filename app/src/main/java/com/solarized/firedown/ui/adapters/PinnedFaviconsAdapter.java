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
        /** Long-press routes to the bookmark options sheet
         *  (open / edit / share / unpin / delete). */
        default void onFaviconLongClick(@NonNull WebBookmarkEntity entity) {}
    }

    private static final DiffUtil.ItemCallback<WebBookmarkEntity> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull WebBookmarkEntity a, @NonNull WebBookmarkEntity b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull WebBookmarkEntity a, @NonNull WebBookmarkEntity b) {
                    // Re-bind when the icon, the url (drives the
                    // domain-name title), or the bookmark title
                    // (used as a fallback when domain extraction
                    // fails) change.
                    return safeEq(a.getIcon(), b.getIcon())
                            && safeEq(a.getUrl(), b.getUrl())
                            && safeEq(a.getTitle(), b.getTitle());
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
        private final TextView title;
        @Nullable private final OnFaviconClickListener listener;
        @Nullable private WebBookmarkEntity bound;

        FaviconViewHolder(@NonNull View itemView, @Nullable OnFaviconClickListener listener) {
            super(itemView);
            this.favicon = itemView.findViewById(R.id.pinned_favicon_image);
            this.title = itemView.findViewById(R.id.pinned_favicon_title);
            this.listener = listener;
            itemView.setOnClickListener(v -> {
                if (listener != null && bound != null) listener.onFaviconClick(bound);
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null && bound != null) {
                    listener.onFaviconLongClick(bound);
                    return true;
                }
                return false;
            });
        }

        void bind(@NonNull WebBookmarkEntity entity, @NonNull RequestOptions options) {
            bound = entity;
            GlideHelper.load(entity.getIcon(), entity.getUrl(), favicon, options);
            title.setText(brandFromUrl(entity.getUrl(), entity.getTitle()));
        }

        /**
         * Extract the 'brand' segment of a URL — the short, recognisable
         * part that NTP shortcut labels use. 'm.youtube.com' → 'youtube',
         * 'startpage.com' → 'startpage', 'giphy.com' → 'giphy'. Strips
         * www / m / mobile subdomain prefixes and the trailing TLD.
         * Two-part ccTLDs like '.co.uk' yield slightly-imperfect labels
         * ('youtube.co' instead of 'youtube') — acceptable cosmetic
         * compromise vs shipping a Public Suffix List.
         *
         * <p>Falls back to the bookmark title (then the raw url) when
         * extraction returns empty so the tile always has a label
         * rather than rendering a blank line under the favicon.</p>
         */
        private static String brandFromUrl(@Nullable String url, @Nullable String title) {
            if (url != null && !url.isEmpty()) {
                String host = WebUtils.getDomainName(url); // already strips www.
                if (host != null && !host.isEmpty()) {
                    host = host.replaceFirst("^(m|mobile)\\.", "");
                    int lastDot = host.lastIndexOf('.');
                    String brand = lastDot > 0 ? host.substring(0, lastDot) : host;
                    if (!brand.isEmpty()) return brand;
                }
            }
            if (title != null && !title.isEmpty()) return title;
            return url == null ? "" : url;
        }
    }
}
