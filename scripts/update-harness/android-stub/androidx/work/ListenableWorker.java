package androidx.work;
public abstract class ListenableWorker {
    public ListenableWorker(android.content.Context c, WorkerParameters p) {}
    public final android.content.Context getApplicationContext() { return null; }
    public final Data getInputData() { return null; }
    public final boolean isStopped() { return false; }
    public final java.util.UUID getId() { return null; }
    public abstract static class Result {
        public static Result success() { return null; }
        public static Result success(Data d) { return null; }
        public static Result retry() { return null; }
        public static Result failure() { return null; }
    }
}
