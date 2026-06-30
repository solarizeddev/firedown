package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;

import com.solarized.firedown.R;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Per-item bottom sheet for a backed-up file: Restore to Downloads / Remove from
 * cloud. Mirrors the Downloads options sheet (BaseBottomSheetDialogFragment +
 * Firedown.Widget.DialogOption rows). The choice is returned to
 * {@link CloudBackupListFragment} via the NavBackStackEntry saved-state handle.
 */
@AndroidEntryPoint
public class CloudBackupItemSheetDialogFragment extends BaseBottomSheetDialogFragment {

    public static final String ARG_OBJECT_ID = "cb_object_id";
    public static final String ARG_NAME = "cb_name";

    /** Saved-state key the list fragment observes; value is a Bundle (below). */
    public static final String RESULT = "cb_item_result";
    public static final String RESULT_ACTION = "action";
    public static final String RESULT_OBJECT_ID = "object_id";
    public static final int ACTION_RESTORE = 0;
    public static final int ACTION_REMOVE = 1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_cloud_backup_item_sheet, container, false);
        Bundle args = getArguments();
        TextView title = mView.findViewById(R.id.cb_sheet_title);
        title.setText(args != null ? args.getString(ARG_NAME) : null);
        mView.findViewById(R.id.cb_sheet_restore).setOnClickListener(v -> dispatch(ACTION_RESTORE));
        mView.findViewById(R.id.cb_sheet_remove).setOnClickListener(v -> dispatch(ACTION_REMOVE));
        return mView;
    }

    private void dispatch(int action) {
        Bundle args = getArguments();
        String objectId = args != null ? args.getString(ARG_OBJECT_ID) : null;
        NavBackStackEntry prev = mNavController.getPreviousBackStackEntry();
        if (prev != null && objectId != null) {
            Bundle result = new Bundle();
            result.putInt(RESULT_ACTION, action);
            result.putString(RESULT_OBJECT_ID, objectId);
            prev.getSavedStateHandle().set(RESULT, result);
        }
        mNavController.popBackStack();
    }
}
