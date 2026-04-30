package com.solarized.firedown.settings.ui;


public class ValidationResult {

    public enum DohStatus { IDLE, LOADING, SUCCESS, ERROR }

    public final DohStatus status;

    public final String url;
    public ValidationResult(DohStatus status, String url) {
        this.status = status;
        this.url = url;
    }
}
