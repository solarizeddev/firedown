package com.solarized.firedown.data.models;



import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.TaskRepository;

import java.util.ArrayList;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class TaskViewModel extends ViewModel {

    private final TaskRepository mTaskRepository;

    @Inject
    public TaskViewModel(TaskRepository repository) {
        this.mTaskRepository = repository;
    }

    public LiveData<Integer> getRegularCount() {
        return mTaskRepository.getRegularCount();
    }

    public LiveData<Integer> getSafeCount() {
        return mTaskRepository.getSafeCount();
    }

    // --- Type-safe events (preferred) ---

    public LiveData<TaskEvent> getObservableEvent() {
        return mTaskRepository.getObservableEvent();
    }

    public void sendEvent(TaskEvent event) {
        mTaskRepository.sendEvent(event);
    }

    public boolean isTaskInFlight() {
        return mTaskRepository.isTaskInFlight();
    }

    // --- Delete action (moved from dialog) ---

    /**
     * Requests deletion of the given download entities.
     * The actual service start is handled by the repository.
     */
    public void requestDelete(Context context, ArrayList<DownloadEntity> entities) {
        mTaskRepository.requestDelete(context, entities);
    }
}