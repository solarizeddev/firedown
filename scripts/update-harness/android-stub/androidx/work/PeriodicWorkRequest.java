package androidx.work;
public final class PeriodicWorkRequest extends WorkRequest {
    public static final class Builder {
        public Builder(Class<? extends ListenableWorker> c, long interval, java.util.concurrent.TimeUnit u) {}
        public Builder(Class<? extends ListenableWorker> c, long interval, java.util.concurrent.TimeUnit u, long flex, java.util.concurrent.TimeUnit fu) {}
        public Builder setConstraints(Constraints c) { return this; }
        public Builder setInitialDelay(long d, java.util.concurrent.TimeUnit u) { return this; }
        public Builder addTag(String tag) { return this; }
        public PeriodicWorkRequest build() { return null; }
    }
}
