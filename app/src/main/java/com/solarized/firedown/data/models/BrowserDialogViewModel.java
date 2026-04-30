package com.solarized.firedown.data.models;

import android.util.Pair;

import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.SingleLiveEvent;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.entity.OptionEntity;

public class BrowserDialogViewModel extends ViewModel {

    private final SingleLiveEvent<Pair<ContextElementEntity, Integer>> contextEvent = new SingleLiveEvent<>();

    private final SingleLiveEvent<Boolean> loadingEvent = new SingleLiveEvent<>();

    private final SingleLiveEvent<OptionEntity> optionsEvent = new SingleLiveEvent<>();

    public SingleLiveEvent<Pair<ContextElementEntity, Integer>> getContextEvent() {
        return contextEvent;
    }

    public SingleLiveEvent<OptionEntity> getOptionsEvent(){
        return optionsEvent;
    }

    public void onEventSelected(ContextElementEntity contextElementEntity, int event) {
        contextEvent.setValue(Pair.create(contextElementEntity, event));
    }

    public void onOptionSelected(OptionEntity optionEntity){
        optionsEvent.setValue(optionEntity);
    }

    public SingleLiveEvent<Boolean> getLoadingEvent(){
        return loadingEvent;
    }

    public void setLoading(boolean value){
        loadingEvent.setValue(value);
    }
}
