package com.solarized.firedown.data;


import com.solarized.firedown.data.entity.CertificateInfoEntity;

public interface TabState {
    int getId();
    String getTitle();
    String getUri();
    String getThumb();
    String getPreview();
    String getSessionState();
    String getIcon();
    int getTabId();
    int getParentId();
    long getCreationDate();
    boolean isActive();
    boolean isFullScreen();
    boolean isSearchMode();
    boolean canGoForward();
    boolean canGoBackward();
    boolean isDesktop();
    boolean useTrackingProtection();
    boolean isHome();
    boolean isFirstContentFulPaint();
    boolean isIncognito();
    int getIconResolution();
    CertificateInfoEntity getCertificateState();
}
