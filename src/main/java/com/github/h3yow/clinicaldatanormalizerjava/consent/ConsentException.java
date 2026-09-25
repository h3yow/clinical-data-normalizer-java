package com.github.h3yow.clinicaldatanormalizerjava.consent;

public class ConsentException extends RuntimeException {

    public enum Kind { NO_CONSENT, OUT_OF_SCOPE, EXPIRED }

    private final Kind kind;

    public ConsentException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
