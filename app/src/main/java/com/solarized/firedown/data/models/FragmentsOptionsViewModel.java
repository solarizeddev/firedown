package com.solarized.firedown.data.models;

import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.SingleLiveEvent;
import com.solarized.firedown.data.entity.OptionEntity;

public class FragmentsOptionsViewModel extends ViewModel {

    private final SingleLiveEvent<OptionEntity> optionsEvent = new SingleLiveEvent<>();

    public SingleLiveEvent<OptionEntity> getOptionsEvent() {
        return optionsEvent;
    }

    public void onOptionsSelected(OptionEntity optionEntity) {
        optionsEvent.setValue(optionEntity);
    }
}
