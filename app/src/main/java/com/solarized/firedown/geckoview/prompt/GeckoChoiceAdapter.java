package com.solarized.firedown.geckoview.prompt;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.TextView;

import androidx.annotation.NonNull;


import org.mozilla.geckoview.GeckoSession;

public class GeckoChoiceAdapter extends ArrayAdapter<PromptViewFactory.ModifiableChoice> {
    private static final int TYPE_MENU_ITEM = 0;
    private static final int TYPE_MENU_CHECK = 1;
    private static final int TYPE_SEPARATOR = 2;
    private static final int TYPE_GROUP = 3;
    private static final int TYPE_SINGLE = 4;
    private static final int TYPE_MULTIPLE = 5;
    private static final int TYPE_COUNT = 6;

    private final int promptType;
    private final LayoutInflater inflater;
    private View separatorView;

    public GeckoChoiceAdapter(Context context, int promptType) {
        super(context, android.R.layout.simple_list_item_1);
        this.inflater = LayoutInflater.from(context);
        this.promptType = promptType;
    }

    @Override
    public int getViewTypeCount() { return TYPE_COUNT; }

    @Override
    public int getItemViewType(int position) {
        PromptViewFactory.ModifiableChoice item = getItem(position);
        if (item == null) return TYPE_MENU_ITEM;
        if (item.choice.separator) return TYPE_SEPARATOR;

        if (promptType == GeckoSession.PromptDelegate.ChoicePrompt.Type.MENU) {
            return item.modifiableSelected ? TYPE_MENU_CHECK : TYPE_MENU_ITEM;
        } else if (item.choice.items != null) {
            return TYPE_GROUP;
        } else if (promptType == GeckoSession.PromptDelegate.ChoicePrompt.Type.SINGLE) {
            return TYPE_SINGLE;
        } else if (promptType == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            return TYPE_MULTIPLE;
        }
        return TYPE_MENU_ITEM;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        int itemType = getItemViewType(position);

        // Handle Separator
        if (itemType == TYPE_SEPARATOR) {
            if (separatorView == null) {
                separatorView = new View(getContext());
                separatorView.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));
                TypedArray attr = getContext().obtainStyledAttributes(new int[]{android.R.attr.listDivider});
                separatorView.setBackgroundResource(attr.getResourceId(0, 0));
                attr.recycle();
            }
            return separatorView;
        }

        // Determine Layout
        int layoutId = switch (itemType) {
            case TYPE_MENU_CHECK -> android.R.layout.simple_list_item_checked;
            case TYPE_GROUP -> android.R.layout.preference_category;
            case TYPE_SINGLE -> android.R.layout.simple_list_item_single_choice;
            case TYPE_MULTIPLE -> android.R.layout.simple_list_item_multiple_choice;
            default -> android.R.layout.simple_list_item_1;
        };

        if (convertView == null) {
            convertView = inflater.inflate(layoutId, parent, false);
        }

        PromptViewFactory.ModifiableChoice item = getItem(position);
        if (item != null) {
            TextView text = (TextView) convertView;
            text.setEnabled(!item.choice.disabled);
            text.setText(item.modifiableLabel);

            if (convertView instanceof CheckedTextView checkedView) {
                checkedView.setChecked(item.modifiableSelected);
            }
        }
        return convertView;
    }
}
