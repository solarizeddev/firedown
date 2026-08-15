package com.solarized.firedown.data.repository;
import com.solarized.firedown.geckoview.GeckoState;
public class IncognitoStateRepository {
    public static GeckoState current;
    public boolean isCurrentGeckoState(GeckoState s) { return s == current; }
}
