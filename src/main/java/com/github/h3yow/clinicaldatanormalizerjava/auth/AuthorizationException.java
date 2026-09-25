package com.github.h3yow.clinicaldatanormalizerjava.auth;

public class AuthorizationException extends RuntimeException {
    private final AuthErrorReason reason;

    public AuthorizationException(AuthErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AuthErrorReason getReason() {
        return reason;
    }
}
