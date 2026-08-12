package org.mozilla.geckoview;
public class GeckoSessionSettings {
    public static class Builder {
        public Builder usePrivateMode(boolean b){ return this; }
        public Builder suspendMediaWhenInactive(boolean b){ return this; }
        public Builder allowJavascript(boolean b){ return this; }
        public GeckoSessionSettings build(){ return new GeckoSessionSettings(); }
    }
}
