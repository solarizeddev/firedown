package androidx.appcompat.app;
public class AlertDialog {
    public interface OnDismissListener { void onDismiss(AlertDialog d); }
    public boolean shown, dismissed;
    private OnDismissListener listener;
    public void setOnDismissListener(OnDismissListener l) { listener = l; }
    public void show() { shown = true; }
    public void dismiss() {
        if (dismissed) return;
        dismissed = true; shown = false;
        if (listener != null) listener.onDismiss(this);
    }
}
