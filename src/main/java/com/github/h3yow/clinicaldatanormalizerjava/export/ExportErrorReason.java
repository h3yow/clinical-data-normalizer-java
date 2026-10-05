package com.github.h3yow.clinicaldatanormalizerjava.export;

/**
 * 导出阶段的错误原因（授权失败见 auth 包，此处为版本相关）。
 */
public enum ExportErrorReason {
    /** 指定的版本号不是正整数 */
    INVALID_VERSION,
    /** 该患者不存在指定版本（含版本号超出当前范围、或属于其他患者的版本） */
    VERSION_NOT_FOUND
}
