package androidx.lifecycle;
public class MutableLiveData<T> extends LiveData<T> {
    public MutableLiveData() {}
    public MutableLiveData(T value) {}
    @Override public void setValue(T value) {}
    @Override public void postValue(T value) {}
}
