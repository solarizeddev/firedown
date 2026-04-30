package com.solarized.firedown.data.di;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.App;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.geckoview.PriorityTaskThreadPoolExecutor;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;


@Module
@InstallIn(SingletonComponent.class) // This makes the bindings available to the whole app
public class AppModule {

    @Provides
    @Singleton
    public SharedPreferences provideSharedPreferences(@ApplicationContext Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Provides
    @Singleton
    public PriorityTaskThreadPoolExecutor providePriorityExecutor() {
        return new PriorityTaskThreadPoolExecutor();
    }


    @Provides
    @Singleton
    public static GeckoMediaController provideMediaController(@ApplicationContext Context context) {
        return new GeckoMediaController(context);
    }


    @Provides
    @Qualifiers.AppVersion // You can add this to your Qualifiers class
    public int provideVersionCode() {
        return App.getVersionCode();
    }


}