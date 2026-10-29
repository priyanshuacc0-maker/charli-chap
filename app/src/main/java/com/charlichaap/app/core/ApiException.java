package com.charlichaap.app.core;

/** A request failure that maps to an HTTP status and a JSON error message. */
public class ApiException extends Exception {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
