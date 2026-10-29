package androidx.core.app;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public class NotificationCompat {
    public static class Builder {
        private final Notification n = new Notification();
        private final List<Notification.Action> actions = new ArrayList<>();
        public Builder(Context context, String channelId) { n.channelId = channelId; }
        public Builder setSmallIcon(int icon) { n.smallIcon = icon; return this; }
        public Builder setContentTitle(CharSequence t) { n.title = t; return this; }
        public Builder setContentText(CharSequence t) { n.text = t; return this; }
        public Builder setContentIntent(PendingIntent pi) { n.contentIntent = pi; return this; }
        public Builder setOngoing(boolean b) { n.ongoing = b; return this; }
        public Builder setOnlyAlertOnce(boolean b) { n.onlyAlertOnce = b; return this; }
        public Builder setSilent(boolean b) { n.silent = b; return this; }
        public Builder setShowWhen(boolean b) { n.showWhen = b; return this; }
        public Builder addAction(int icon, CharSequence title, PendingIntent intent) { actions.add(new Notification.Action(icon, title, intent)); return this; }
        public Builder setProgress(int max, int progress, boolean indeterminate) {
            n.hasProgress = true; n.progressMax = max; n.progress = progress; n.progressIndeterminate = indeterminate; return this;
        }
        public Notification build() {
            Notification out = new Notification();
            out.channelId = n.channelId; out.smallIcon = n.smallIcon; out.title = n.title; out.text = n.text; out.contentIntent = n.contentIntent;
            out.ongoing = n.ongoing; out.onlyAlertOnce = n.onlyAlertOnce; out.silent = n.silent; out.showWhen = n.showWhen;
            out.hasProgress = n.hasProgress; out.progressMax = n.progressMax; out.progress = n.progress; out.progressIndeterminate = n.progressIndeterminate;
            return out.copyActions(actions);
        }
    }
}
