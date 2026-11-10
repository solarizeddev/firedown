package androidx.core.app;
public class NotificationCompat {
    public static final String CATEGORY_PROGRESS = "progress";
    public static final int PRIORITY_LOW = -1;
    public static class Builder {
        public Builder(android.content.Context c, String channelId) {}
        public Builder setSmallIcon(int icon) { return this; }
        public Builder setOnlyAlertOnce(boolean b) { return this; }
        public Builder setSilent(boolean b) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setAutoCancel(boolean b) { return this; }
        public Builder setShowWhen(boolean b) { return this; }
        public Builder setWhen(long when) { return this; }
        public Builder setCategory(String c) { return this; }
        public Builder setContentTitle(CharSequence t) { return this; }
        public Builder setContentText(CharSequence t) { return this; }
        public Builder setContentIntent(android.app.PendingIntent p) { return this; }
        public Builder setProgress(int max, int progress, boolean indeterminate) { return this; }
        public android.app.Notification build() { return null; }
    }
}
