package androidx.work;
public abstract class ListenableWorker {
    private final android.content.Context context;
    private final WorkerParameters params;
    public volatile boolean stopped;
    public ListenableWorker(android.content.Context c, WorkerParameters p) { context = c; params = p; }
    public final android.content.Context getApplicationContext() { return context; }
    public final Data getInputData() { return params.input; }
    public final boolean isStopped() { return stopped; }
    public static class Result {
        public final String name;
        private Result(String n) { name = n; }
        public static Result success() { return new Result("success"); }
        public static Result success(Data d) { return new Result("success"); }
        public static Result retry() { return new Result("retry"); }
        public static Result failure() { return new Result("failure"); }
        @Override public String toString() { return name; }
    }
}
