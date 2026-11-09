package androidx.work;
public abstract class Worker extends ListenableWorker {
    public Worker(android.content.Context c, WorkerParameters p) { super(c, p); }
    public abstract Result doWork();
    public ForegroundInfo getForegroundInfo() { throw new IllegalStateException(); }
}
