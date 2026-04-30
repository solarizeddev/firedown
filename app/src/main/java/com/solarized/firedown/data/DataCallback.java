package com.solarized.firedown.data;

public interface DataCallback<T> {
    void onComplete(T result);
    default void onError(Throwable t) { /* Optional error handling */ }
}