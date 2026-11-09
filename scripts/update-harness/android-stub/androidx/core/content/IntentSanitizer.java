package androidx.core.content;
public class IntentSanitizer {
    public static final class Builder {
        public Builder allowAnyComponent() { return this; }
        public Builder allowExtra(String key, Class<?> clazz) { return this; }
        public Builder allowFlags(int flags) { return this; }
        public Builder allowAction(String action) { return this; }
        public Builder allowPackage(androidx.core.util.Predicate<String> filter) { return this; }
    }
}
