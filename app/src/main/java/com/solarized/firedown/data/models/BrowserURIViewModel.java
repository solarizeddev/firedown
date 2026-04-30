package com.solarized.firedown.data.models;


import androidx.core.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.entity.GeckoStateEntity;

/**
 * ViewModel that routes URI/session events from IntentHandler to BrowserFragment.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li><b>IntentHandler</b> owns all navigation and tab activation.
 *       It fires events here only to pass the entity + action to BrowserFragment
 *       so BrowserFragment can call openSession() / openUri().</li>
 *   <li><b>BrowserFragment</b> observes events, acts on them (openSession/openUri),
 *       then calls {@link #clearEvent()} to prevent replay on config change.</li>
 *   <li><b>HomeFragment</b> does NOT observe this stream.</li>
 * </ul>
 *
 * <p>Uses plain {@link MutableLiveData} with explicit clear-after-consume,
 * avoiding the fragile SingleLiveEvent pattern that caused event-loss bugs
 * when multiple fragments observed the same stream.</p>
 */
public class BrowserURIViewModel extends ViewModel {

    private final MutableLiveData<Pair<GeckoStateEntity, String>> optionsEvent = new MutableLiveData<>();

    public LiveData<Pair<GeckoStateEntity, String>> getEvents() {
        return optionsEvent;
    }

    public void onEventSelected(GeckoStateEntity geckoStateEntity, String event) {
        optionsEvent.setValue(Pair.create(geckoStateEntity, event));
    }

    /**
     * Clears the current event to prevent re-delivery on config change
     * or fragment re-subscription.  Must be called by BrowserFragment
     * after processing an event.
     */
    public void clearEvent() {
        optionsEvent.setValue(null);
    }

    public boolean hasPendingEvent() {
        return optionsEvent.getValue() != null;
    }

}