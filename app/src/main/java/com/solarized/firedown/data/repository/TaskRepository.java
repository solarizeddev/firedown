package com.solarized.firedown.data.repository;

import android.content.Context;
import android.content.Intent;
import android.os.Message;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TaskRepository {

    private final MutableLiveData<Integer> mObservableCount = new MutableLiveData<>(0);

    private final MutableLiveData<TaskEvent> mObservableEvent = new MutableLiveData<>();

    @Inject
    public TaskRepository() {}

    // --- Count ---

    public LiveData<Integer> getObservableCount() {
        return mObservableCount;
    }

    public void updateCount(int count) {
        mObservableCount.postValue(count);
    }

    // --- Type-safe events (new) ---

    public LiveData<TaskEvent> getObservableEvent() {
        return mObservableEvent;
    }

    public void sendEvent(TaskEvent event) {
        mObservableEvent.postValue(event);
    }


    // --- Delete action (moved from DeleteDownloadsDialogFragment) ---

    /**
     * Starts the delete operation via RunnableManager service.
     * This keeps the service-start logic in the repository layer
     * so fragments/dialogs don't need direct access to the Service class.
     */
    public void requestDelete(Context context, ArrayList<DownloadEntity> entities) {
        if (entities == null || entities.isEmpty())
            return;
        Intent intent = new Intent(context, RunnableManager.class);
        intent.putExtra(Keys.ITEM_LIST_ID, entities);
        intent.setAction(IntentActions.DOWNLOAD_DELETE);
        context.startService(intent);
    }

}