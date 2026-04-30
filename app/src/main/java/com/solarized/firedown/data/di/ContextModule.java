package com.solarized.firedown.data.di;

import android.content.ClipboardManager;
import android.content.Context;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public class ContextModule {

    @Provides
    @Singleton
    public ClipboardManager provideClipboardManager(@ApplicationContext Context context) {
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }


    @Provides
    @Singleton
    @Qualifiers.MainThread // <--- Add this label here!
    public Executor provideMainThreadExecutor(@ApplicationContext Context context) {
        return ContextCompat.getMainExecutor(context);
    }

    @Provides
    @Singleton
    @Qualifiers.DiskIO // Label it!
    public Executor provideDiskExecutor() {
        return Executors.newSingleThreadExecutor();
    }


    @Provides
    @Singleton
    @Qualifiers.AutoComplete // Label it!
    public ExecutorService provideAutoCompleteExecutor() {
        return Executors.newFixedThreadPool(4);
    }


    @Provides
    @Singleton
    @Qualifiers.Network // Label it!
    public Executor provideNetworkExecutor() {
        return Executors.newSingleThreadExecutor();
    }


}