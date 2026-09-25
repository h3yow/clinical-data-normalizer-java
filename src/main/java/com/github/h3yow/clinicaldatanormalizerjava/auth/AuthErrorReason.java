package com.github.h3yow.clinicaldatanormalizerjava.auth;

/**
 * 授权失败的可区分原因。
 */
public enum AuthErrorReason {
    /** 授权不存在或未登记 */
    GRANT_NOT_FOUND,
    /** 授权已过期（expiresAt &lt;= 当前时间） */
    GRANT_EXPIRED,
    /** 授权不包含目标患者 */
    PATIENT_OUT_OF_SCOPE,
    /** 请求的数据大类超出授权范围 */
    CATEGORY_OUT_OF_SCOPE
}
