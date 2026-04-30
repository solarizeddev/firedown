package com.solarized.firedown.phone.dialogs;


import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;

import java.util.ArrayList;


public class DownloadsDialogFragment extends BaseDialogFragment {


    private static final String TAG = DownloadsDialogFragment.class.getSimpleName();


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        final ArrayList<Integer> enabled = new ArrayList<>();

        final ArrayList<CharSequence> choices = new ArrayList<>();

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        int checkedItem = sharedPreferences.getInt(Preferences.SETTINGS_DOWNLOADS, Preferences.DEFAULT_DOWNLOADS);

        if(!StoragePaths.isSDCardAvailable(mActivity)){
            enabled.add(CustomAdapter.SDCARD_POSITION);
            checkedItem = Preferences.DEFAULT_DOWNLOADS;
            choices.add(getString(R.string.settings_downloads_free, StoragePaths.getAvailableInternalMemorySize()));
            choices.add(getString(R.string.settings_downloads_sdcard_not_detected));
        }else{
            choices.add(getString(R.string.settings_downloads_free, StoragePaths.getAvailableInternalMemorySize()));
            choices.add(getString(R.string.settings_downloads_sdcard_free, StoragePaths.getAvailableSDCardMemorySize(mActivity)));
        }

        final CustomAdapter customAdapter = new CustomAdapter(requireContext(), choices, enabled);

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.settings_downloads_path))
                .setSingleChoiceItems(customAdapter, checkedItem, (dialog, which) -> sharedPreferences.edit().putInt(Preferences.SETTINGS_DOWNLOADS, which).apply())
                .setPositiveButton(getString(android.R.string.ok), (dialog, which) -> {
                    mNavController.popBackStack();
                } )
                .create();
    }

    private static class CustomAdapter extends BaseAdapter {

        public static final int DOWNLOADS_POSITION = 0;

        public static final int SDCARD_POSITION = 1;

        private final ArrayList<String> mData = new ArrayList<>();

        private final LayoutInflater mInflater;

        private final ArrayList<Integer> mDisabled;

        public CustomAdapter(Context context, ArrayList<CharSequence> items,
                               ArrayList<Integer> disabledItems) {
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mDisabled = disabledItems;
            for (CharSequence item : items) {
                addItem(item.toString());
            }
        }

        public void addItem(final String item) {
            mData.add(item);

            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mData.size();
        }

        @Override
        public String getItem(int position) {
            return mData.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return !mDisabled.contains(position);
        }


        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                holder = new ViewHolder();
                convertView = mInflater.inflate(androidx.appcompat.R.layout.select_dialog_singlechoice_material, null);
                holder.textView = convertView.findViewById(android.R.id.text1);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder)convertView.getTag();
            }
            holder.textView.setText(Html.fromHtml(mData.get(position)));
            holder.textView.setEnabled(isEnabled(position));
            return convertView;
        }

    }

    private static class ViewHolder {

        CheckedTextView textView;

    }


}
