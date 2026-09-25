package com.github.h3yow.clinicaldatanormalizerjava.hl7;

public class Hl7ParseException extends RuntimeException {
    private final ParseErrorReason reason;

    public Hl7ParseException(ParseErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ParseErrorReason getReason() {
        return reason;
    }
}
