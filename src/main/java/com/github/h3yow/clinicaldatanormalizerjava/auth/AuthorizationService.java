package com.github.h3yow.clinicaldatanormalizerjava.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 授权服务：内存登记授权许可，并对导出请求做最小必要披露校验。
 *
 * <p>校验顺序（每种失败对应独立的 {@link AuthErrorReason}）：
 * <ol>
 *   <li>授权必须存在；</li>
 *   <li>授权必须未过期（expiresAt 严格晚于当前时间）；</li>
 *   <li>目标患者必须在授权患者清单内；</li>
 *   <li>请求的每个数据大类都必须在授权大类清单内（最小必要原则）。</li>
 * </ol>
 */
@Service
public class AuthorizationService {

    private final Map<String, AuthorizationGrant> grants = new LinkedHashMap<>();

    @PostConstruct
    public void seedDemoGrants() {
        Instant future = Instant.now().plus(365, ChronoUnit.DAYS);
        // 有效授权：仅生命体征
        register(new AuthorizationGrant("grant-vitals-demo",
                Set.of("P-0001"), Set.of("vital_signs"), future));
        // 有效授权：生命体征 + 检验
        register(new AuthorizationGrant("grant-all-demo",
                Set.of("P-0001", "P-0002"), Set.of("vital_signs", "laboratory"), future));
        // 已过期授权（固定为 2020 年，测试与演示用）
        register(new AuthorizationGrant("grant-expired-demo",
                Set.of("P-0001"), Set.of("vital_signs", "laboratory"),
                Instant.parse("2020-01-01T00:00:00Z")));
    }

    public void register(AuthorizationGrant grant) {
        grants.put(grant.grantId(), grant);
    }

    public AuthorizationGrant get(String grantId) {
        return grants.get(grantId);
    }

    /**
     * 校验导出请求，通过则返回授权许可。
     *
     * @param categories 请求的数据大类集合（必须为授权范围的子集）
     */
    public AuthorizationGrant validate(String grantId, String patientId,
                                       Set<String> categories, Instant now) {
        AuthorizationGrant grant = grants.get(grantId);
        if (grant == null) {
            throw new AuthorizationException(AuthErrorReason.GRANT_NOT_FOUND,
                    "授权不存在: " + grantId);
        }
        if (!grant.expiresAt().isAfter(now)) {
            throw new AuthorizationException(AuthErrorReason.GRANT_EXPIRED,
                    "授权已于 " + grant.expiresAt() + " 过期");
        }
        if (!grant.patientIds().contains(patientId)) {
            throw new AuthorizationException(AuthErrorReason.PATIENT_OUT_OF_SCOPE,
                    "授权 " + grantId + " 不包含患者 " + patientId);
        }
        for (String category : categories) {
            if (!grant.categories().contains(category)) {
                throw new AuthorizationException(AuthErrorReason.CATEGORY_OUT_OF_SCOPE,
                        "授权 " + grantId + " 不包含数据大类 " + category
                                + "（授权范围: " + grant.categories() + "）");
            }
        }
        return grant;
    }
}
