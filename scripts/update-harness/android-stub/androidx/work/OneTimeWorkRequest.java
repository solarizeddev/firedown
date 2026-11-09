package androidx.work;
public final class OneTimeWorkRequest extends WorkRequest {
    public static final class Builder {
        public Builder(Class<? extends ListenableWorker> c) {}
        public Builder setInputData(Data d) { return this; }
        public Builder setConstraints(Constraints c) { return this; }
        public Builder setExpedited(OutOfQuotaPolicy p) { return this; }
        public Builder setInitialDelay(long d, java.util.concurrent.TimeUnit u) { return this; }
        public Builder addTag(String tag) { return this; }
        public OneTimeWorkRequest build() { return null; }
    }
}
