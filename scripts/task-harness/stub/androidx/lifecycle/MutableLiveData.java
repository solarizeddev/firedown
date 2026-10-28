package androidx.lifecycle;
public class MutableLiveData<T> extends LiveData<T> {
    public MutableLiveData() { super(); }
    public MutableLiveData(T value) { super(value); }
    @Override public void postValue(T value) { super.postValue(value); }
    @Override public void setValue(T value) { super.setValue(value); }
}
