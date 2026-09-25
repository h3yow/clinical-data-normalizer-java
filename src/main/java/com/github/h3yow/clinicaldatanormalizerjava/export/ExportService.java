package com.github.h3yow.clinicaldatanormalizerjava.export;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationGrant;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.PatientRecord;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 去标识化导出服务。
 *
 * <p>最小必要披露边界：
 * <ul>
 *   <li>导出前必须通过 {@link AuthorizationService} 校验：授权存在、未过期、
 *       覆盖目标患者、请求大类不超范围，任一不满足即拒绝且不产生数据。</li>
 *   <li>输出不含任何直接标识符：无患者 ID、无来源系统、无消息控制号；
 *       患者身份替换为带服务盐的确定性伪名（SHA-256），同一患者跨次导出一致，
 *       但不可逆推回原 ID。</li>
 *   <li>时间精度降低到年份（effectiveYear），避免日期级重标识。</li>
 *   <li>仅导出授权大类内的观测；完全相同的去标识化行合并为一行。</li>
 * </ul>
 *
 * <p>可复现性：数据版本（患者纵向合并版本号）与授权范围（大类清单）相同时，
 * 观测集合、伪名、摘要 digest 完全一致。digest 为对规范化排序后内容的 SHA-256。
 */
@Service
public class ExportService {

    private final IngestionService ingestionService;
    private final AuthorizationService authorizationService;
    private final String pseudonymSalt;

    public ExportService(IngestionService ingestionService,
                         AuthorizationService authorizationService,
                         @Value("${app.export.pseudonym-salt:clinical-normalizer-default-pepper}")
                         String pseudonymSalt) {
        this.ingestionService = ingestionService;
        this.authorizationService = authorizationService;
        this.pseudonymSalt = pseudonymSalt;
    }

    public ExportBundle export(String grantId, String patientId, Set<String> categories) {
        Set<String> requested = categories == null ? Set.of() : new LinkedHashSet<>(categories);
        AuthorizationGrant grant = authorizationService.validate(grantId, patientId, requested, Instant.now());

        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            // 未入库的患者不泄露其存在性：返回空集
            return buildBundle(pseudonym(patientId), 0, requested, List.of());
        }

        List<DeidentifiedObservation> rows = deidentify(patientId, requested);
        return buildBundle(pseudonym(patientId), record.version(), requested, rows);
    }

    /**
     * 不做授权校验的去标识化（仅限服务内部/测试直接使用；REST 路径必须走 export）。
     */
    public List<DeidentifiedObservation> deidentify(String patientId, Set<String> categories) {
        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            return List.of();
        }
        Set<String> allowed = categories == null ? Set.of() : categories;
        List<DeidentifiedObservation> rows = new ArrayList<>();
        for (Observation obs : record.observations()) {
            if (!allowed.contains(obs.category())) {
                continue;
            }
            int year = obs.effectiveTime().atZone(java.time.ZoneOffset.UTC).getYear();
            rows.add(new DeidentifiedObservation(
                    obs.category(),
                    obs.code(),
                    obs.displayName(),
                    obs.value(),
                    obs.unit(),
                    year));
        }
        // 合并完全相同的去标识化行，并按全字段确定序排序
        return rows.stream()
                .distinct()
                .sorted(Comparator.comparing(DeidentifiedObservation::category)
                        .thenComparing(DeidentifiedObservation::code)
                        .thenComparingInt(DeidentifiedObservation::effectiveYear)
                        .thenComparingDouble(DeidentifiedObservation::value)
                        .thenComparing(DeidentifiedObservation::unit)
                        .thenComparing(DeidentifiedObservation::displayName))
                .toList();
    }

    private ExportBundle buildBundle(String pseudonym, long version,
                                     Set<String> categories,
                                     List<DeidentifiedObservation> rows) {
        List<String> scopes = categories.stream().sorted().toList();
        String digest = digest(pseudonym, version, scopes, rows);
        return new ExportBundle(pseudonym, version, scopes, rows, digest);
    }

    /** 规范化拼接后做 SHA-256：版本+范围+逐行内容，任何字段不同都会改变摘要。 */
    private String digest(String pseudonym, long version, List<String> scopes,
                          List<DeidentifiedObservation> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1|").append(pseudonym).append('|').append(version).append('|');
        sb.append(String.join(",", scopes)).append('\n');
        for (DeidentifiedObservation r : rows) {
            sb.append(r.category()).append('|')
                    .append(r.code()).append('|')
                    .append(r.displayName()).append('|')
                    .append(Double.toString(r.value())).append('|')
                    .append(r.unit()).append('|')
                    .append(r.effectiveYear()).append('\n');
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 患者 ID + 固定服务盐的确定性伪名。 */
    private String pseudonym(String patientId) {
        return "PSEUDO-" + sha256Hex(("v1|" + pseudonymSalt + '|' + patientId)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
