package androidx.lifecycle;
public abstract class LiveData<T> {
    public LiveData() {}
    public LiveData(T value) {}
    public T getValue() { return null; }
    protected void setValue(T value) {}
    protected void postValue(T value) {}
    protected void onActive() {}
    protected void onInactive() {}
}
