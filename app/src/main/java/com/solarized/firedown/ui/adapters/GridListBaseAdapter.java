package com.solarized.firedown.ui.adapters;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;


public abstract class GridListBaseAdapter<T, VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {

    private static final String TAG = GridListBaseAdapter.class.getName();

    protected static final int TYPE_GRID = 0;

    protected static final int TYPE_LIST = 1;

    /** Dense grid — the images-only filtered mosaic (square bare tiles at a
     *  denser span). A distinct view type, not a bind-time flag, because the
     *  RecycledViewPool keys holders by view type: reusing TYPE_GRID across
     *  a density toggle would hand back holders from the wrong layout. */
    protected static final int TYPE_GRID_DENSE = 2;

    public boolean mList;

    protected boolean mDense;

    final AsyncListDiffer<T> mDiffer;
    private final AsyncListDiffer.ListListener<T> mListener =
            new AsyncListDiffer.ListListener<T>() {
                @Override
                public void onCurrentListChanged(
                        @NonNull List<T> previousList, @NonNull List<T> currentList) {
                    GridListBaseAdapter.this.onCurrentListChanged(previousList, currentList);
                }
            };

    @SuppressWarnings("unused")
    protected GridListBaseAdapter(@NonNull DiffUtil.ItemCallback<T> diffCallback) {
        mDiffer = new AsyncListDiffer<>(new OffsetListUpdateCallback(),
                new AsyncDifferConfig.Builder<>(diffCallback).build());
        mDiffer.addListListener(mListener);
    }

    @SuppressWarnings("unused")
    protected GridListBaseAdapter(@NonNull AsyncDifferConfig<T> config) {
        mDiffer = new AsyncListDiffer<>(new OffsetListUpdateCallback(), config);
        mDiffer.addListListener(mListener);
    }

    /**
     * Number of header items the adapter renders before the diffed data.
     * Default is 0 — subclasses that prepend their own rows (e.g. a
     * single-shot banner) override this and keep its value in sync with
     * {@link #getItemCount()} / {@link #getItemViewType(int)}.
     *
     * <p>The offset is folded into every DiffUtil-driven
     * {@code notifyItem*} call so AsyncListDiffer's per-position updates
     * line up with the adapter's actual layout — without this, an insert
     * at differ-index 0 would notify position 0 (the banner row) and the
     * RecyclerView would animate the wrong slot.</p>
     */
    protected int getPositionOffset() {
        return 0;
    }

    /**
     * Submits a new list to be diffed, and displayed.
     * <p>
     * If a list is already being displayed, a diff will be computed on a background thread, which
     * will dispatch Adapter.notifyItem events on the main thread.
     *
     * @param list The new list to be displayed.
     */
    public void submitList(@Nullable List<T> list) {
        mDiffer.submitList(list);
    }

    /**
     * Set the new list to be displayed.
     * <p>
     * If a List is already being displayed, a diff will be computed on a background thread, which
     * will dispatch Adapter.notifyItem events on the main thread.
     * <p>
     * The commit callback can be used to know when the List is committed, but note that it
     * may not be executed. If List B is submitted immediately after List A, and is
     * committed directly, the callback associated with List A will not be run.
     *
     * @param list The new list to be displayed.
     * @param commitCallback Optional runnable that is executed when the List is committed, if
     *                       it is committed.
     */
    public void submitList(@Nullable List<T> list, @Nullable final Runnable commitCallback) {
        mDiffer.submitList(list, commitCallback);
    }

    protected T getItem(int position) {
        return mDiffer.getCurrentList().get(position);
    }

    @Override
    public int getItemCount() {
        return mDiffer.getCurrentList().size();
    }

    /**
     * Get the current List - any diffing to present this list has already been computed and
     * dispatched via the ListUpdateCallback.
     * <p>
     * If a <code>null</code> List, or no List has been submitted, an empty list will be returned.
     * <p>
     * The returned list may not be mutated - mutations to content must be done through
     * {@link #submitList(List)}.
     *
     * @return The list currently being displayed.
     *
     * @see #onCurrentListChanged(List, List)
     */
    @NonNull
    public List<T> getCurrentList() {
        return mDiffer.getCurrentList();
    }

    /**
     * Called when the current List is updated.
     * <p>
     * If a <code>null</code> List is passed to {@link #submitList(List)}, or no List has been
     * submitted, the current List is represented as an empty List.
     *
     * @param previousList List that was displayed previously.
     * @param currentList new List being displayed, will be empty if {@code null} was passed to
     *          {@link #submitList(List)}.
     *
     * @see #getCurrentList()
     */
    public void onCurrentListChanged(@NonNull List<T> previousList, @NonNull List<T> currentList) {
    }


    @SuppressLint("NotifyDataSetChanged")
    public void enableGrid(boolean list){
        mList = list;
        notifyDataSetChanged();
    }

    /**
     * Flips the grid between normal tiles and the dense mosaic. Must notify
     * itself (unlike a submitList-driven change): the differ sees the same
     * items and emits nothing, but their view TYPE changed — without the
     * notify, holders bound before the flip would keep the stale layout.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void enableDenseGrid(boolean dense) {
        if (mDense == dense) {
            return;
        }
        mDense = dense;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position){
        if (mList) {
            return TYPE_LIST;
        }
        return mDense ? TYPE_GRID_DENSE : TYPE_GRID;
    }

    /**
     * Translates differ-space positions into adapter-space before calling
     * the corresponding {@code notifyItem*} method. The stock
     * {@code AdapterListUpdateCallback} passes positions through
     * verbatim, which only works when the diffed list starts at adapter
     * position 0 — see {@link #getPositionOffset()} for why we need the
     * shift.
     */
    private final class OffsetListUpdateCallback implements ListUpdateCallback {
        @Override
        public void onInserted(int position, int count) {
            notifyItemRangeInserted(position + getPositionOffset(), count);
        }

        @Override
        public void onRemoved(int position, int count) {
            notifyItemRangeRemoved(position + getPositionOffset(), count);
        }

        @Override
        public void onMoved(int fromPosition, int toPosition) {
            int offset = getPositionOffset();
            notifyItemMoved(fromPosition + offset, toPosition + offset);
        }

        @Override
        public void onChanged(int position, int count, @Nullable Object payload) {
            notifyItemRangeChanged(position + getPositionOffset(), count, payload);
        }
    }
}