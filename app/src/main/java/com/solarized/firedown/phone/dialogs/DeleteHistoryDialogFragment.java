package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.WebHistoryViewModel;

public class DeleteHistoryDialogFragment extends BaseDialogFragment {

    private int mSelectedPosition = 0;

    private WebHistoryViewModel mWebHistoryViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);

    }


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default
        final View view = LayoutInflater.from(mActivity).inflate(R.layout.fragment_web_history_delete, null);
        final MaterialAutoCompleteTextView autoCompleteTextView = view.findViewById(R.id.auto_complete_view);
        final ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(getActivity(),R.array.delete_history,android.R.layout.simple_list_item_1);
        autoCompleteTextView.setOnItemClickListener((adapterView, view1, position, l) -> mSelectedPosition = position);
        autoCompleteTextView.setText(arrayAdapter.getItem(0));
        autoCompleteTextView.setAdapter(arrayAdapter);
        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.delete_history_prompt_title))
                .setView(view)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    mWebHistoryViewModel.deleteSelection(mSelectedPosition);
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismiss();
                } )
                .create();
    }


}
