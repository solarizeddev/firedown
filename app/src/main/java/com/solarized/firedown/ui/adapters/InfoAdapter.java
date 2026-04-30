package com.solarized.firedown.ui.adapters;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.InfoEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.Utils;

public class InfoAdapter extends ListAdapter<InfoEntity, RecyclerView.ViewHolder> {


    private final OnItemClickListener mOnItemClickListener;

    private final DownloadEntity mDownloadEntity;


    public static class FinalViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

        private final OnItemClickListener mOnItemClickListener;

        public FinalViewHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            // Define click listener for the ViewHolder's View
            View mButton = view.findViewById(R.id.button);
            mButton.setOnClickListener(this);
            mOnItemClickListener = onItemClickListener;
        }

        @Override
        public void onClick(View view) {
            int position = getAbsoluteAdapterPosition();
            if(mOnItemClickListener != null){
                mOnItemClickListener.onItemClick(position, view.getId());
            }
        }


    }

    /**
     * Provide a reference to the type of views that you are using
     * (custom ViewHolder).
     */
    public static class ItemViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

        private final TextView textView;

        private final TextView titleView;

        private final OnItemClickListener mOnItemClickListener;

        public ItemViewHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            // Define click listener for the ViewHolder's View
            View item = view.findViewById(R.id.item);
            textView = view.findViewById(R.id.item_details);
            titleView = view.findViewById(R.id.item_details_title);
            item.setOnClickListener(this);
            mOnItemClickListener = onItemClickListener;
        }

        @Override
        public void onClick(View view) {
            int position = getAbsoluteAdapterPosition();
            if(mOnItemClickListener != null){
                mOnItemClickListener.onItemClick(position, view.getId());
            }
        }


    }

    public InfoAdapter(@NonNull DiffUtil.ItemCallback<InfoEntity> diffCallback, OnItemClickListener onItemClickListener, DownloadEntity downloadEntity){
        super(diffCallback);
        mDownloadEntity = downloadEntity;
        mOnItemClickListener = onItemClickListener;
    }


    // Create new views (invoked by the layout manager)
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        // Create a new view, which defines the UI of the list item
        if(viewType == InfoEntity.ITEM_FINAL){
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_dialog_info_item_final, viewGroup, false);
            return new FinalViewHolder(view, mOnItemClickListener);
        }else{
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_dialog_info_item, viewGroup, false);
            return new ItemViewHolder(view, mOnItemClickListener);
        }
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, final int position) {

        // Get element from your dataset at this position and replace the
        // contents of the view with that element
        //viewHolder.textView.setEnabled(FileUriHelper.isVideo(mMimeType));
        int viewType = getItemViewType(position);

        InfoEntity entity = getItem(position);

        if(viewType == InfoEntity.ITEM){
            ItemViewHolder holder = (ItemViewHolder) viewHolder;

            holder.titleView.setText(entity.getText());

            String duration = mDownloadEntity.getDurationFormatted();

            int id = entity.getId();

            if(id == R.id.info_details_name) {
                holder.textView.setText(mDownloadEntity.getFileName());
            } else if(id == R.id.info_details_description) {
                holder.textView.setText(mDownloadEntity.getFileDescription());
            } else if(id == R.id.info_details_size) {
                holder.textView.setText(Utils.getFileSize(mDownloadEntity.getFileSize()));
            } else if(id == R.id.info_details_modified) {
                holder.textView.setText(DateUtils.getFileDate(mDownloadEntity.getFileDate()));
            } else if(id == R.id.info_details_origin) {
                holder.textView.setText(mDownloadEntity.getOriginUrl());
            } else if(id == R.id.info_details_path) {
                holder.textView.setText(mDownloadEntity.getFilePath());
            } else if(id == R.id.info_details_duration) {
                holder.textView.setText(TextUtils.isEmpty(duration) ? "00:00" : duration);
            } else if(id == R.id.info_details_url) {
                holder.textView.setText(mDownloadEntity.getFileUrl());
            }

        }
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

}
