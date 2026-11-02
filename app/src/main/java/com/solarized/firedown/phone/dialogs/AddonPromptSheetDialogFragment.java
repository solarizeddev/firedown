package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonIcons;
import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AddonPermissionText;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The add-on permission question: adding an add-on, an update that asks
 * for more, a permission it asks for while running. Shown by the activity
 * in front ({@code BaseActivity}) for {@link AddonManager#getPrompt()};
 * Gecko waits for the answer, so leaving the sheet any way but the confirm
 * button answers "no".
 */
@AndroidEntryPoint
public class AddonPromptSheetDialogFragment extends BaseBottomSheetDialogFragment {

    public static final String TAG = "AddonPrompt";
    private static final String ARG_SEQ = "seq";

    @Inject
    AddonManager mAddonManager;

    private int mSeq;
    private boolean mAnswered;

    @NonNull
    public static AddonPromptSheetDialogFragment newInstance(int seq) {
        AddonPromptSheetDialogFragment f = new AddonPromptSheetDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SEQ, seq);
        f.setArguments(args);
        return f;
    }

    public int getSeq() {
        return mSeq;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSeq = getArguments() == null ? 0 : getArguments().getInt(ARG_SEQ);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_addon_prompt, container, false);
        AddonManager.Prompt prompt = mAddonManager.getPrompt().getValue();
        if (prompt == null || prompt.seq != mSeq) {
            // Answered elsewhere (or a sheet restored after the app was gone).
            mAnswered = true;
            dismissAllowingStateLoss();
            return mView;
        }
        bind(prompt);
        return mView;
    }

    private void bind(@NonNull AddonManager.Prompt prompt) {
        ImageView icon = mView.findViewById(R.id.addon_prompt_icon);
        TextView title = mView.findViewById(R.id.addon_prompt_title);
        TextView source = mView.findViewById(R.id.addon_prompt_source);
        View notForAndroid = mView.findViewById(R.id.addon_prompt_android);
        TextView can = mView.findViewById(R.id.addon_prompt_can);
        LinearLayout lines = mView.findViewById(R.id.addon_prompt_lines);
        TextView data = mView.findViewById(R.id.addon_prompt_data);
        View privateRow = mView.findViewById(R.id.addon_prompt_private_row);
        MaterialSwitch privateSwitch = mView.findViewById(R.id.addon_prompt_private_switch);
        MaterialButton cancel = mView.findViewById(R.id.addon_prompt_cancel);
        MaterialButton confirm = mView.findViewById(R.id.addon_prompt_confirm);

        String name = prompt.addon.name;
        int iconPx = getResources().getDimensionPixelSize(R.dimen.addon_prompt_icon_size);
        AddonIcons.load(requireContext(), prompt.addon.icon, iconPx, drawable -> {
            if (isAdded()) {
                icon.setImageDrawable(drawable);
            }
        });

        switch (prompt.type) {
            case AddonManager.Prompt.UPDATE:
                title.setText(getString(R.string.addon_prompt_update_title, name));
                confirm.setText(R.string.addon_prompt_update);
                break;
            case AddonManager.Prompt.OPTIONAL:
                title.setText(getString(R.string.addon_prompt_optional_title, name));
                confirm.setText(R.string.addon_prompt_allow);
                break;
            default:
                title.setText(getString(R.string.addon_prompt_install_title, name));
                confirm.setText(R.string.addon_prompt_add);
                break;
        }

        if (prompt.source != null) {
            source.setText(getString(R.string.addon_prompt_source, prompt.source));
            source.setVisibility(View.VISIBLE);
        }
        notForAndroid.setVisibility(prompt.notMadeForAndroid ? View.VISIBLE : View.GONE);

        List<String> texts = AddonPermissionText.lines(requireContext(), prompt.permissions, prompt.origins);
        if (texts.isEmpty()) {
            can.setText(R.string.addon_prompt_no_permissions);
        } else {
            can.setText(prompt.type == AddonManager.Prompt.UPDATE
                    ? R.string.addon_prompt_update_can : R.string.addon_prompt_can);
            for (String text : texts) {
                lines.addView(lineView(lines, text));
            }
        }
        String dataText = AddonPermissionText.dataCollection(requireContext(), prompt.dataCollection);
        if (dataText != null) {
            data.setText(dataText);
            data.setVisibility(View.VISIBLE);
        }

        boolean offerPrivate = prompt.type == AddonManager.Prompt.INSTALL && prompt.addon.canRunInPrivate();
        privateRow.setVisibility(offerPrivate ? View.VISIBLE : View.GONE);
        privateRow.setOnClickListener(v -> privateSwitch.toggle());

        cancel.setOnClickListener(v -> answer(false, false));
        confirm.setOnClickListener(v -> answer(true, offerPrivate && privateSwitch.isChecked()));
    }

    @NonNull
    private TextView lineView(@NonNull LinearLayout parent, @NonNull String text) {
        TextView view = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.item_addon_prompt_line, parent, false);
        view.setText(getString(R.string.addon_prompt_line, text));
        return view;
    }

    private void answer(boolean allow, boolean allowPrivate) {
        if (mAnswered) {
            return;
        }
        mAnswered = true;
        mAddonManager.answer(mSeq, allow, allowPrivate);
        dismissAllowingStateLoss();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // Back, a swipe down or a tap outside: "no". A rotation doesn't get
        // here (the activities handle it themselves); an activity finishing
        // with the sheet up answers through onDestroy.
        if (!mAnswered) {
            mAnswered = true;
            mAddonManager.dismissPrompt(mSeq);
        }
    }

    @Override
    public void onDestroy() {
        Activity activity = getActivity();
        boolean changing = activity != null && activity.isChangingConfigurations();
        if (!mAnswered && !changing) {
            mAnswered = true;
            mAddonManager.dismissPrompt(mSeq);
        }
        super.onDestroy();
    }
}
