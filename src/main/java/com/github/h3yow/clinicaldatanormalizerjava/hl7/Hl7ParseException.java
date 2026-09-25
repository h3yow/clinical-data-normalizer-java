package com.github.h3yow.clinicaldatanormalizerjava.hl7;

public class Hl7ParseException extends RuntimeException {
    private final RejectionReason reason;

    public Hl7ParseException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
