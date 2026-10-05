package com.github.h3yow.clinicaldatanormalizerjava.export;

/**
 * 导出阶段异常：携带可区分的 {@link ExportErrorReason}。
 */
public class ExportException extends RuntimeException {

    private final ExportErrorReason reason;

    public ExportException(ExportErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ExportErrorReason getReason() {
        return reason;
    }
}
