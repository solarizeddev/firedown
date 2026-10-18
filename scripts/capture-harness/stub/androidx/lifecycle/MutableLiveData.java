package androidx.lifecycle;
/** Test stand-in: holds the last posted value. */
public class MutableLiveData<T> { private T value; public void postValue(T v) { value = v; } public T getValue() { return value; } }
