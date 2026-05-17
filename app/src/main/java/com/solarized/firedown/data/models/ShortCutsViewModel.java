package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.data.repository.ShortCutsDataRepository;
import com.solarized.firedown.geckoview.GeckoState;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class ShortCutsViewModel extends ViewModel {

    private final ShortCutsDataRepository mRepository;

    @Inject
    public ShortCutsViewModel(ShortCutsDataRepository repository) {
        this.mRepository = repository;
    }

    public LiveData<List<ShortCutsEntity>> getShortCuts() {
        return mRepository.getShortCuts();
    }

    public void update(ShortCutsEntity shortCutsEntity) {
        mRepository.update(shortCutsEntity);
    }

    public void delete(ShortCutsEntity shortCutsEntity) {
        mRepository.delete(shortCutsEntity);
    }

    public void delete(GeckoState geckoState) {
        mRepository.delete(geckoState);
    }

    public void add(GeckoState geckoState) {
        mRepository.add(geckoState);
    }

    /**
     * Manual add from the home empty-state CTA, where the user types
     * a name + URL rather than pinning the currently-loaded page.
     * Limit enforcement is the caller's responsibility — check
     * {@link #isFull()} first.
     */
    public void add(ShortCutsEntity entity) {
        mRepository.add(entity);
    }

    public boolean contains(GeckoState geckoState) {
        return mRepository.contains(geckoState);
    }

    public boolean isFull(){
        return mRepository.isFull();
    }
}
