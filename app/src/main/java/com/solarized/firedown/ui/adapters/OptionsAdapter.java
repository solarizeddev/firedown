package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import java.util.List;

public class OptionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_FINAL = 1;

    private final List<OptionItem> mItems;
    private final OnItemClickListener mListener;
    @LayoutRes private final int mItemLayout;
    @LayoutRes private final int mFinalLayout;
    private final boolean mHasFinalItem;

    public interface OnItemClickListener {
        void onItemClick(int position, OptionItem item);
    }

    /**
     * Full constructor — different layouts, with a distinct final item.
     */
    public OptionsAdapter(List<OptionItem> items, OnItemClickListener listener,
                          @LayoutRes int itemLayout, @LayoutRes int finalLayout, boolean hasFinalItem) {
        this.mItems = items;
        this.mListener = listener;
        this.mItemLayout = itemLayout;
        this.mFinalLayout = finalLayout;
        this.mHasFinalItem = hasFinalItem;
    }

    /**
     * Simple constructor — uniform layout, no distinct final item.
     */
    public OptionsAdapter(List<OptionItem> items, OnItemClickListener listener,
                          @LayoutRes int itemLayout) {
        this.mItems = items;
        this.mListener = listener;
        this.mItemLayout = itemLayout;
        this.mFinalLayout = itemLayout; // unused but avoids null
        this.mHasFinalItem = false;
    }

    /**
     * Default constructor — uses the standard dialog item layouts with a final item.
     * Matches the original behavior.
     */
    public OptionsAdapter(List<OptionItem> items, OnItemClickListener listener) {
        this(items, listener,
                R.layout.fragment_dialog_options_item,
                R.layout.fragment_dialog_options_item_final, true);
    }

    public OptionsAdapter(List<OptionItem> items, OnItemClickListener listener, boolean hasFinal) {
        this(items, listener,
                R.layout.fragment_dialog_options_item,
                R.layout.fragment_dialog_options_item_final,
                hasFinal);
    }

    @Override
    public int getItemViewType(int position) {
        if (mHasFinalItem && position == mItems.size() - 1) return TYPE_FINAL;
        return TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layout = (viewType == TYPE_FINAL) ? mFinalLayout : mItemLayout;
        View view = inflater.inflate(layout, parent, false);
        return new BaseViewHolder(view, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof BaseViewHolder baseHolder) {
            baseHolder.bind(mItems.get(position));
        }
    }

    @Override
    public int getItemCount() {
        return mItems != null ? mItems.size() : 0;
    }

    static class BaseViewHolder extends RecyclerView.ViewHolder {
        private final TextView textView;
        private OptionItem currentItem;

        public BaseViewHolder(View view, OnItemClickListener listener) {
            super(view);
            this.textView = view.findViewById(R.id.item_options);
            view.setOnClickListener(v -> {
                if (listener != null && getAbsoluteAdapterPosition() != RecyclerView.NO_POSITION) {
                    listener.onItemClick(getAbsoluteAdapterPosition(), currentItem);
                }
            });
        }

        public void bind(OptionItem item) {
            this.currentItem = item;
            textView.setText(item.getLabel());
            if (item.hasIcon()) {
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(item.getIconRes(), 0, 0, 0);
            } else {
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            }
        }
    }
}