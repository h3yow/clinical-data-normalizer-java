package com.github.h3yow.clinicaldatanormalizerjava.normalize;

public class NormalizationException extends RuntimeException {
    private final NormalizationErrorReason reason;

    public NormalizationException(NormalizationErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public NormalizationErrorReason getReason() {
        return reason;
    }
}
