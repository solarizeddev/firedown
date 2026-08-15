package com.solarized.firedown.geckoview;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import org.mozilla.geckoview.GeckoSession;
/** Thin per-tab state: booleans, the deactivate hook, and the REAL
 *  PromptFloodGate (copied from app source by run.sh — the flood decision is
 *  never faked). deactivate() mirrors the real setActive(false) contract:
 *  run + clear the hook. */
public class GeckoState {
    private final GeckoStateEntity mEntity = new GeckoStateEntity();
    private final PromptFloodGate mFloodGate = new PromptFloodGate();
    private boolean mPromptDisplaying;
    private Runnable mOnDeactivateAction;
    private final GeckoSession mSession = new GeckoSession();

    public GeckoStateEntity getGeckoStateEntity() { return mEntity; }
    public GeckoSession getGeckoSession() { return mSession; }
    public void setPromptDisplaying(boolean v) { mPromptDisplaying = v; }
    public boolean isPromptDisplaying() { return mPromptDisplaying; }
    public void setOnDeactivateAction(Runnable r) { mOnDeactivateAction = r; }
    public void recordPromptShown() { mFloodGate.recordPromptShown(); }
    public boolean isPromptFlooding() { return mFloodGate.isPromptFlooding(); }
    /** Tab switched away — the real GeckoState runs and clears the hook. */
    public void deactivate() {
        Runnable r = mOnDeactivateAction;
        mOnDeactivateAction = null;
        if (r != null) r.run();
    }
}
