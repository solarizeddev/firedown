package com.solarized.firedown.geckoview.prompt;

import android.app.Activity;
import android.content.Context;
import android.text.Html;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TimePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoState;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

public class PromptViewFactory {

    private static final String TAG = PromptViewFactory.class.getSimpleName();

    public interface PromptResponseHandler {
        void onResponse(GeckoSession session, PromptDelegate.PromptResponse response);
    }

    public interface PermissionResponseHandler {
        void onPermission(GeckoSession session, int value);
    }

    // --- CHOICE PROMPT ---
    public static AlertDialog createChoiceDialog(Activity activity, GeckoState state, PromptDelegate.ChoicePrompt prompt, PromptResponseHandler handler) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state));
        builder.setTitle(prompt.title);

        GeckoChoiceAdapter adapter = new GeckoChoiceAdapter(activity, prompt.type);
        addChoiceItems(prompt.type, adapter, prompt.choices, null);

        ListView list = new ListView(activity);
        list.setAdapter(adapter);

        if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                ArrayList<String> selectedIds = new ArrayList<>();
                for (int i = 0; i < adapter.getCount(); i++) {
                    ModifiableChoice item = adapter.getItem(i);
                    if (item != null && item.modifiableSelected) selectedIds.add(item.choice.id);
                }
                handler.onResponse(state.getGeckoSession(), prompt.confirm(selectedIds.toArray(new String[0])));
            });
        } else {
            list.setOnItemClickListener((p, v, pos, id) -> {
                ModifiableChoice item = adapter.getItem(pos);
                if (item != null) {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(item.choice));
                }
                state.setPromptDisplaying(false);
            });
        }
        builder.setView(list);
        return setupDismissListener(builder.create(), state, prompt, handler);
    }

    // --- COLOR PROMPT ---
    public static AlertDialog createColorDialog(Activity activity, GeckoState state, PromptDelegate.ColorPrompt prompt, PromptResponseHandler handler) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state));
        builder.setTitle(prompt.title);

        ListView list = new ListView(activity);
        ColorAdapter adapter = new ColorAdapter(activity, android.R.layout.simple_list_item_1);
        adapter.addSelectedColor(prompt.defaultValue);

        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);
        builder.setView(list);

        list.setOnItemClickListener((parent, v, position, id) -> {
            list.setItemChecked(position, true);
            Integer integer = adapter.getItem(position);
            if(integer == null) integer = 0;
            adapter.setSelectedColor(integer);
            adapter.notifyDataSetChanged();
        });


        builder.setPositiveButton(R.string.prompts_choose_a_color, (d, w) -> {
            int pos = list.getCheckedItemPosition();
            Integer color = adapter.getItem(Math.max(pos, 0));
            handler.onResponse(state.getGeckoSession(),
                    prompt.confirm(String.format("#%06x", 0xffffff & (color != null ? color : 0))));
        });
        return setupDismissListener(builder.create(), state, prompt, handler);
    }


    // --- BUTTON PROMPT (Confirm) ---
    public static AlertDialog createButtonDialog(Activity activity, GeckoState state, PromptDelegate.ButtonPrompt prompt, PromptResponseHandler handler) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state))
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE));
                });

        return setupDismissListener(builder.create(), state, prompt, handler);
    }

    // --- TEXT PROMPT (Input) ---
    public static AlertDialog createTextDialog(Activity activity, GeckoState state, PromptDelegate.TextPrompt prompt, PromptResponseHandler handler) {
        final EditText input = new EditText(activity);
        input.setText(prompt.defaultValue);

        // Add margin/padding to the EditText so it's not flush against dialog edges
        FrameLayout container = new FrameLayout(activity);
        int padding = activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding_standard);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state))
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(input.getText().toString()));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.dismiss());
                });

        return setupDismissListener(builder.create(), state, prompt, handler);
    }


    public static AlertDialog createContentPermissionDialog(
            Activity activity,
            GeckoState state,
            GeckoSession.PermissionDelegate.ContentPermission permission,
            String message,
            PermissionResponseHandler handler) {

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state));

        // Handle special DRM / Media Key UI
        if (permission.permission == GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS) {
            builder.setView(R.layout.fragment_dialog_drm);
        }

        // Modern HTML parsing for the message (e.g., "Allow <b>google.com</b> to use camera?")
        builder.setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(R.string.prompts_allow, (d, w) -> {
                    handler.onPermission(state.getGeckoSession(),
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    handler.onPermission(state.getGeckoSession(),
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
                });

        AlertDialog dialog = builder.create();

        // Centralized state cleanup
        dialog.setOnDismissListener(d -> {
            state.setPromptDisplaying(false);
            // If no choice was made (dismissed by back/click-out), default to DENY or PROMPT
            handler.onPermission(state.getGeckoSession(),
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
        });

        return dialog;
    }

    public static AlertDialog createRepostDialog(
            Activity activity,
            GeckoState state,
            GeckoSession.PromptDelegate.RepostConfirmPrompt prompt,
            PromptResponseHandler handler) {

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state))
                .setTitle(R.string.prompt_repost_title)
                .setMessage(R.string.prompt_repost_message)
                .setPositiveButton(R.string.prompt_repost_positive_button_text, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(AllowOrDeny.ALLOW));
                })
                .setNegativeButton(R.string.prompt_repost_negative_button_text, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(AllowOrDeny.DENY));
                });

        // We pass the prompt cast to BasePrompt for the universal dismiss listener
        return setupDismissListener(builder.create(), state, prompt, handler);
    }



    public static AlertDialog createAlertDialog(
            Activity activity,
            GeckoState state,
            PromptDelegate.AlertPrompt prompt,
            PromptResponseHandler handler) {

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state))
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), null);
                });


        // setupDismissListener ensures state.setPromptDisplaying(false) is called
        // and prompt.dismiss() is sent if the user clicks outside the dialog.
        return setupDismissListener(builder.create(), state, prompt, handler);
    }

    public static AlertDialog createAuthDialog(
            Activity activity,
            GeckoState state,
            PromptDelegate.AuthPrompt prompt,
            PromptResponseHandler handler) {

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state));
        builder.setTitle(prompt.title);
        if (prompt.message != null) {
            builder.setMessage(prompt.message);
        }

        // Container with standard margins
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = activity.getResources().getDimensionPixelSize(R.dimen.dialog_padding_standard);
        container.setPadding(padding, padding, padding, 0);

        final int flags = prompt.authOptions.flags;
        final EditText usernameField;

        // 1. Username Field (Optional based on flags)
        if ((flags & PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) == 0) {
            usernameField = new EditText(activity);
            usernameField.setHint(R.string.prompt_username_hint);
            usernameField.setText(prompt.authOptions.username);
            container.addView(usernameField);
        } else {
            usernameField = null;
        }

        // 2. Password Field
        final EditText passwordField = new EditText(activity);
        passwordField.setHint(R.string.prompt_password_hint);
        passwordField.setText(prompt.authOptions.password);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(passwordField);

        // 3. Secure Icon (Security Level Indicator)
        if (prompt.authOptions.level != PromptDelegate.AuthPrompt.AuthOptions.Level.NONE) {
            ImageView secureIcon = new ImageView(activity);
            secureIcon.setImageResource(android.R.drawable.ic_lock_lock);
            secureIcon.setPadding(0, padding / 2, 0, 0);
            container.addView(secureIcon);
        }

        // Wrap in ScrollView to handle small screens + keyboard
        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(container);
        builder.setView(scrollView);

        builder.setNegativeButton(android.R.string.cancel, (d, w) -> {
            handler.onResponse(state.getGeckoSession(), prompt.dismiss());
        });

        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
            String user = (usernameField != null) ? usernameField.getText().toString() : "";
            String pass = passwordField.getText().toString();

            // Return response based on Gecko's required signature
            if (usernameField != null) {
                handler.onResponse(state.getGeckoSession(), prompt.confirm(user, pass));
            } else {
                handler.onResponse(state.getGeckoSession(), prompt.confirm(pass));
            }
        });

        return setupDismissListener(builder.create(), state, prompt, handler);
    }

    public static AlertDialog createBeforeUnloadDialog(
            Activity activity,
            GeckoState state,
            PromptDelegate.BeforeUnloadPrompt prompt,
            PromptResponseHandler handler) {

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state));

        // Usually, the message is "Changes you made may not be saved."
        builder.setTitle(R.string.prompt_before_unload_dialog_title)
                .setMessage(R.string.prompt_before_unload_dialog_body)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(AllowOrDeny.ALLOW));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(AllowOrDeny.DENY));
                });

        return setupDismissListener(builder.create(), state, prompt, handler);
    }

    // --- DATE/TIME PROMPT ---
    public static AlertDialog createDateTimeDialog(Activity activity, GeckoState state, PromptDelegate.DateTimePrompt prompt, PromptResponseHandler handler) {
        String format = getFormatString(prompt.type);
        SimpleDateFormat formatter = new SimpleDateFormat(format, Locale.ROOT);
        Calendar cal = Calendar.getInstance();
        try {
            if (prompt.defaultValue != null)
                cal.setTime(formatter.parse(prompt.defaultValue));
        } catch (Exception e) {
            Log.e(TAG, "createTimeDialog", e);
        }

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        DatePicker dp = null;
        if (hasDate(prompt.type)) {
            dp = new DatePicker(activity);
            dp.init(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), null);
            container.addView(dp);
        }

        TimePicker tp = null;
        if (hasTime(prompt.type)) {
            tp = new TimePicker(activity);
            tp.setHour(cal.get(Calendar.HOUR_OF_DAY));
            tp.setMinute(cal.get(Calendar.MINUTE));
            container.addView(tp);
        }


        final DatePicker fDp = dp; final TimePicker fTp = tp;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, resolveDialogTheme(state))
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (fDp != null) cal.set(fDp.getYear(), fDp.getMonth(), fDp.getDayOfMonth());
                    if (fTp != null) { cal.set(Calendar.HOUR_OF_DAY, fTp.getHour()); cal.set(Calendar.MINUTE, fTp.getMinute()); }
                    handler.onResponse(state.getGeckoSession(), prompt.confirm(formatter.format(cal.getTime())));
                });

        return setupDismissListener(builder.create(), state, prompt, handler);
    }

    // --- INNER CLASSES (Refactored from original file) ---

    public static class ModifiableChoice {
        public final PromptDelegate.ChoicePrompt.Choice choice;
        public String modifiableLabel;
        public boolean modifiableSelected;

        public ModifiableChoice(PromptDelegate.ChoicePrompt.Choice choice) {
            this.choice = choice;
            this.modifiableLabel = choice.label;
            this.modifiableSelected = choice.selected;
        }
    }

    private static class ColorAdapter extends ArrayAdapter<Integer> {
        public ColorAdapter(Context context, int resource) {
            super(context, resource); // Ensure this layout exists in your res folder


            mInflater = LayoutInflater.from(context);

            add(Color.RED);
            add(Color.GREEN);
            add(Color.BLUE);
            add(Color.YELLOW);
            add(Color.CYAN);
            add(Color.MAGENTA);
            add(Color.BLACK);
            add(Color.WHITE);
        }

        private final LayoutInflater mInflater;

        private int mSelectedColor;

        boolean isDark(int color) {
            double result = ColorUtils.calculateLuminance(color);
            return result < 0.5;
        }

        private int parseColor(final String value, final int def) {
            try {
                return Color.parseColor(value);
            } catch (final IllegalArgumentException e) {
                return def;
            }
        }

        public void addSelectedColor(String value){
            mSelectedColor = parseColor(value, /* def */ 0);
            boolean add = true;
            for(int i = 0; i < getCount() ; i++){
                Integer item = getItem(i);
                if(item == null) item = 0;
                if(item == mSelectedColor){
                    add = false;
                    break;
                }
            }
            if(add){
                add(mSelectedColor);
            }
        }

        public void setSelectedColor(int hexColor){
            mSelectedColor = hexColor;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(final int position) {
            Integer integer = getItem(position);
            if(integer != null){
                return (integer == mSelectedColor) ? 1 : 0;
            }
            return 0;
        }

        @NonNull
        @Override
        public View getView(final int position, View view, @NonNull final ViewGroup parent) {

            Integer integer = getItem(position);
            if(integer == null) integer = 0;
            final int color = integer;
            if (view == null) {
                view =
                        mInflater.inflate(
                                (color == mSelectedColor)
                                        ? android.R.layout.simple_list_item_checked
                                        : android.R.layout.simple_list_item_1,
                                parent,
                                false);

            }
            View view1 = view.findViewById(android.R.id.text1);
            if(view1 instanceof CheckedTextView) {
                ((CheckedTextView)view1).setCheckMarkDrawable(isDark(mSelectedColor) ? R.drawable.ic_check_light_40 :  R.drawable.ic_check_dark_40);
            }
            view.setBackgroundResource(android.R.drawable.editbox_background);
            view.getBackground().setColorFilter(color, PorterDuff.Mode.MULTIPLY);
            return view;
        }

    }

    // --- PRIVATE HELPERS ---

    /**
     * Resolves which dialog theme to use based on whether the GeckoState is in incognito mode.
     * This ensures prompt dialogs shown over an incognito tab use the vault-themed styling.
     */
    private static int resolveDialogTheme(GeckoState state) {
        boolean isIncognito = state != null
                && state.getGeckoStateEntity() != null
                && state.getGeckoStateEntity().isIncognito();
        return isIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : 0;
    }

    private static AlertDialog setupDismissListener(AlertDialog dialog, GeckoState state, PromptDelegate.BasePrompt prompt, PromptResponseHandler handler) {
        dialog.setOnDismissListener(d -> {
            state.setPromptDisplaying(false);
            if (!prompt.isComplete()) handler.onResponse(state.getGeckoSession(), prompt.dismiss());
        });
        return dialog;
    }

    private static String getFormatString(int type) {
        return switch (type) {
            case PromptDelegate.DateTimePrompt.Type.TIME -> "HH:mm";
            case PromptDelegate.DateTimePrompt.Type.MONTH -> "yyyy-MM";
            default -> "yyyy-MM-dd";
        };
    }

    private static boolean hasDate(int type) { return type != PromptDelegate.DateTimePrompt.Type.TIME; }
    private static boolean hasTime(int type) { return type == PromptDelegate.DateTimePrompt.Type.TIME; }

    private static void addChoiceItems(int type, GeckoChoiceAdapter adapter, PromptDelegate.ChoicePrompt.Choice[] choices, String indent) {
        for (PromptDelegate.ChoicePrompt.Choice c : choices) {
            ModifiableChoice mc = new ModifiableChoice(c);
            if (indent != null && c.items == null) mc.modifiableLabel = indent + mc.modifiableLabel;
            adapter.add(mc);
            if (c.items != null) addChoiceItems(type, adapter, c.items, (indent == null ? "\t" : indent + "\t"));
        }
    }
}