package androidx.work;
public final class OneTimeWorkRequest {
    public final Class<?> worker; public Data input; public long delayMs;
    OneTimeWorkRequest(Class<?> w) { worker = w; }
    public static final class Builder {
        private final OneTimeWorkRequest r;
        public Builder(Class<? extends ListenableWorker> c) { r = new OneTimeWorkRequest(c); }
        public Builder setInputData(Data d) { r.input = d; return this; }
        public Builder setConstraints(Constraints c) { return this; }
        public Builder setInitialDelay(long d, java.util.concurrent.TimeUnit u) { r.delayMs = u.toMillis(d); return this; }
        public OneTimeWorkRequest build() { return r; }
    }
}
