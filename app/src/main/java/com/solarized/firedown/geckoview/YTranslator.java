package com.solarized.firedown.geckoview;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class YTranslator {

    private YTranslatorStrategy strategy;


    @NonNull
    public YTranslatorStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(@NonNull YTranslatorStrategy yTranslatorStrategy) {
        strategy = yTranslatorStrategy;
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

    public void forceExpandWithAnimation(View view, float distance) {
        strategy.forceExpandWithAnimation(view, distance);
    }

    public void collapseWithAnimation(@NonNull View view) {
        strategy.collapseWithAnimation(view);
    }

    public void forceExpandIfNotAlready(@NonNull View view, float distance) {
        strategy.forceExpandWithAnimation(view, distance);
    }

    public void translate(@NonNull View view, float distance) {
        this.strategy.translate(view, distance);
    }

    public void cancelInProgressTranslation() {
        strategy.cancelInProgressTranslation();
    }

    public YTranslator(ViewPosition viewPosition) {
        super();
        if(viewPosition == ViewPosition.TOP){
            strategy = new YTranslatorStrategy.TopviewBehaviorStrategy();
        }else{
            strategy = new YTranslatorStrategy.BottomviewBehaviorStrategy();
        }

    }
}