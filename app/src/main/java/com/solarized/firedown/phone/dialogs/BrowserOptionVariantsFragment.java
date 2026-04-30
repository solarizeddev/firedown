package com.solarized.firedown.phone.dialogs;


import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.adapters.BrowserOptionVariantAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;


public class BrowserOptionVariantsFragment extends BaseFocusFragment implements OnItemClickListener, View.OnClickListener {

    private BrowserDownloadEntity mEntity;

    private BrowserOptionVariantAdapter mAdapter;

    private FragmentsOptionsViewModel mFragmentsViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();
        if (bundle == null)
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());

        mEntity = bundle.getParcelable(Keys.ITEM_ID);
        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);
    }


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_dialog_browser_options_variants, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        Toolbar toolbar = view.findViewById(R.id.toolbar);

        toolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);
        toolbar.setNavigationOnClickListener(v -> dispatchCancel());

        view.findViewById(R.id.cancel_button).setOnClickListener(this);
        view.findViewById(R.id.button).setOnClickListener(this);

        mAdapter = new BrowserOptionVariantAdapter(mEntity.getStreams(), this);
        recyclerView.setAdapter(mAdapter);

        return view;
    }


    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;
        if (resId == R.id.file_variants_item) {
            mAdapter.setSelected(position);
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.cancel_button) {
            dispatchCancel();
        } else {
            dispatchDownload();
        }
    }

    private void dispatchCancel() {
        OptionEntity optionEntity = new OptionEntity();
        optionEntity.setId(R.id.cancel_button);
        mFragmentsViewModel.onOptionsSelected(optionEntity);
    }

    private void dispatchDownload() {
        FFmpegEntity selectedStream = mAdapter.getSelectedStream();

        // Build an immutable DownloadRequest from the entity + selected stream
        DownloadRequest request = DownloadRequest.from(mEntity, selectedStream);

        OptionEntity optionEntity = new OptionEntity();
        optionEntity.setId(R.id.button);
        optionEntity.setDownloadRequest(request);
        optionEntity.setAction(IntentActions.DOWNLOAD_START);
        mFragmentsViewModel.onOptionsSelected(optionEntity);
    }
}
