package androidx.lifecycle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
/**
 * LiveData's delivery contract, copied in behaviour from androidx: postValue
 * from any thread stores the value as PENDING and posts one task to the main
 * thread; a postValue before that task ran replaces the pending value, so
 * only the last one is dispatched ("If you called this method multiple
 * times before a main thread executed a posted task, only the last value
 * would be dispatched."). setValue dispatches to the (always active)
 * observers on the calling thread, which is main.
 */
public abstract class LiveData<T> {
    static final Object NOT_SET = new Object();
    /** Set by the harness to "post to main"; null runs the task inline. */
    public static volatile Consumer<Runnable> mainPoster;
    /** Harness probe: every postValue, on the posting thread, before it is stored. */
    public static volatile BiConsumer<LiveData<?>, Object> postHook;
    final Object mDataLock = new Object();
    private volatile Object mData;
    private Object mPendingData = NOT_SET;
    private final List<Observer<? super T>> mObservers = new CopyOnWriteArrayList<>();
    public LiveData() { mData = NOT_SET; }
    public LiveData(T value) { mData = value; }
    protected void postValue(T value) {
        BiConsumer<LiveData<?>, Object> h = postHook;
        if (h != null) h.accept(this, value);
        boolean postTask;
        synchronized (mDataLock) {
            postTask = mPendingData == NOT_SET;
            mPendingData = value;
        }
        if (!postTask) return;
        Runnable task = () -> {
            Object v;
            synchronized (mDataLock) {
                v = mPendingData;
                mPendingData = NOT_SET;
            }
            @SuppressWarnings("unchecked") T t = (T) v;
            setValue(t);
        };
        Consumer<Runnable> p = mainPoster;
        if (p != null) p.accept(task); else task.run();
    }
    protected void setValue(T value) {
        mData = value;
        for (Observer<? super T> o : mObservers) o.onChanged(value);
    }
    @SuppressWarnings("unchecked")
    public T getValue() { Object d = mData; return d == NOT_SET ? null : (T) d; }
    public void observe(LifecycleOwner owner, Observer<? super T> observer) { mObservers.add(observer); }
    public void observeForever(Observer<? super T> observer) { mObservers.add(observer); }
    public void removeObserver(Observer<? super T> observer) { mObservers.remove(observer); }
    public boolean hasActiveObservers() { return !mObservers.isEmpty(); }
    public boolean hasObservers() { return !mObservers.isEmpty(); }
}
