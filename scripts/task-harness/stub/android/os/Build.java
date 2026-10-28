package android.os;
public class Build {
    public static class VERSION {
        /** Settable by the harness (not final, so javac never folds it). */
        public static int SDK_INT = 35;
    }
    public static class VERSION_CODES {
        public static final int Q = 29;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
        public static final int VANILLA_ICE_CREAM = 35;
    }
}
