package com.solarized.firedown.ui.adapters;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.AdapterListUpdateCallback;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;


public abstract class GridListBaseAdapter<T, VH extends RecyclerView.ViewHolder>
        extends RecyclerView.Adapter<VH> {

    private static final String TAG = GridListBaseAdapter.class.getName();

    protected static final int TYPE_GRID = 0;

    protected static final int TYPE_LIST = 1;

    public boolean mList;

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
        mDiffer = new AsyncListDiffer<>(new AdapterListUpdateCallback(this),
                new AsyncDifferConfig.Builder<>(diffCallback).build());
        mDiffer.addListListener(mListener);
    }

    @SuppressWarnings("unused")
    protected GridListBaseAdapter(@NonNull AsyncDifferConfig<T> config) {
        mDiffer = new AsyncListDiffer<>(new AdapterListUpdateCallback(this), config);
        mDiffer.addListListener(mListener);
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

    @Override
    public int getItemViewType(int position){
        return mList ? TYPE_LIST : TYPE_GRID;
    }



}