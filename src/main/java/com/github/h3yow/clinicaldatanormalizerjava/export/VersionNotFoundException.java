package com.github.h3yow.clinicaldatanormalizerjava.export;

/**
 * 指定的历史版本不存在（或不属于该患者）时抛出。
 * 与授权失败（403）区分：映射为 HTTP 404 + VERSION_NOT_FOUND。
 */
public class VersionNotFoundException extends RuntimeException {

    public static final String REASON = "VERSION_NOT_FOUND";

    public VersionNotFoundException(String message) {
        super(message);
    }

    public String getReason() {
        return REASON;
    }
}
