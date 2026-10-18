package android.os;
/** Test stand-in: the repository's throttle posts; nothing runs them. */
public class Handler { public Handler(Looper l) {} public boolean postDelayed(Runnable r, long ms) { return true; } public void removeCallbacks(Runnable r) {} }
