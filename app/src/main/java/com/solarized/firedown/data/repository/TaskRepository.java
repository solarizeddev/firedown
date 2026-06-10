package com.solarized.firedown.data.repository;

import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.data.SingleLiveEvent;
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

    /**
     * Active+queued count of regular (non-vault) downloads. Surfaced as
     * the bottom-bar badge for {@code HomeFragment} and the regular-mode
     * {@code BrowserFragment} — keeping vault downloads out of this
     * count so an incognito-tab download doesn't advertise itself in
     * the regular browsing chrome (privacy + matches what the user
     * expects when they're on a regular page).
     */
    private final MutableLiveData<Integer> mRegularCount = new MutableLiveData<>(0);

    /** Active+queued count of vault (incognito-tab / save-to-vault)
     *  downloads. Surfaced as the bottom-bar badge for the
     *  incognito-mode {@code BrowserFragment}. */
    private final MutableLiveData<Integer> mSafeCount = new MutableLiveData<>(0);

    /**
     * One-shot task events (Started / Progress / Finished / Deleted / Error
     * / Cancelled). A {@link SingleLiveEvent} rather than a plain
     * MutableLiveData so the value is consumed once and NOT replayed to a
     * re-subscribing observer — otherwise navigating away from the downloads
     * list and back (e.g. opening the frame grabber / GIF maker, or a
     * rotation) would re-deliver the last event and, say, re-show the
     * "files deleted" snackbar.
     */
    private final SingleLiveEvent<TaskEvent> mObservableEvent = new SingleLiveEvent<>();

    @Inject
    public TaskRepository() {}

    // --- Count ---

    public LiveData<Integer> getRegularCount() {
        return mRegularCount;
    }

    public LiveData<Integer> getSafeCount() {
        return mSafeCount;
    }

    /**
     * Update both regular and vault counts in a single call so observers
     * downstream see a consistent snapshot. {@link RunnableManager}
     * walks its active+queue task lists and bucket-counts by
     * {@link com.solarized.firedown.manager.DownloadTask#isFileSafe()}.
     */
    public void updateCount(int regular, int safe) {
        mRegularCount.postValue(regular);
        mSafeCount.postValue(safe);
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