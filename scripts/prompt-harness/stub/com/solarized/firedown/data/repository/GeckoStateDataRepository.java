package com.solarized.firedown.data.repository;
import com.solarized.firedown.geckoview.GeckoState;
public class GeckoStateDataRepository {
    public static GeckoState current;
    public boolean isCurrentGeckoState(GeckoState s) { return s == current; }
}
