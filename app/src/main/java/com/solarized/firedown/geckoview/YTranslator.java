package com.solarized.firedown.geckoview;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Facade over {@link YTranslatorStrategy}. Since {@code BottomNavigationBehavior} became a
 * passive follower of the toolbar, the top toolbar is the only translated bar, so this
 * always drives a {@link YTranslatorStrategy.TopviewBehaviorStrategy}.
 */
public final class YTranslator {

    private final YTranslatorStrategy strategy;

    public YTranslator() {
        strategy = new YTranslatorStrategy.TopviewBehaviorStrategy();
    }

    public void snapWithAnimation(@NonNull View view) {
        strategy.snapWithAnimation(view);
    }

    public void snapImmediately(@Nullable View view) {
        strategy.snapImmediately(view);
    }

    public void expandWithAnimation(@NonNull View view) {
        strategy.expandWithAnimation(view);
    }

    public void collapseWithAnimation(@NonNull View view) {
        strategy.collapseWithAnimation(view);
    }

    public void translate(@NonNull View view, float distance) {
        strategy.translate(view, distance);
    }

    public void cancelInProgressTranslation() {
        strategy.cancelInProgressTranslation();
    }
}
