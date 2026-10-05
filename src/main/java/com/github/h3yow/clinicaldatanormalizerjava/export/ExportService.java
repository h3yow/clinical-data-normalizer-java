package com.github.h3yow.clinicaldatanormalizerjava.export;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.PatientRecord;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.VersionSnapshot;
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
 *
 * <p>导出口径：只导出患者<strong>当前有效观测集合</strong>（被更正掉、被撤回、
 * 被冲突裁决掉的值不出现）。支持按历史版本追溯导出（{@link #exportVersion}），
 * 历史版本取自固化快照，与当前导出走同一套授权校验与去标识化边界。
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
        authorizationService.validate(grantId, patientId, requested, Instant.now());

        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            // 未入库的患者不泄露其存在性：返回空集
            return buildBundle(pseudonym(patientId), 0, requested, List.of());
        }

        List<DeidentifiedObservation> rows = deidentifyRows(record.effectiveObservations(), requested);
        return buildBundle(pseudonym(patientId), record.version(), requested, rows);
    }

    /**
     * 按历史版本追溯导出（审计复盘）。
     *
     * <p>与当前版本导出走完全相同的授权校验与去标识化边界；数据取自该版本
     * 固化的不可变快照，之后投递再多新数据也不影响结果。同一患者、同一版本、
     * 同一授权范围下任意次导出的观测集合与 digest 完全一致（可复现）。
     *
     * @param versionRaw 版本号（正整数）；非法格式与不存在版本分别抛出
     *                   {@link ExportErrorReason#INVALID_VERSION} 与
     *                   {@link ExportErrorReason#VERSION_NOT_FOUND}，可区分
     */
    public ExportBundle exportVersion(String grantId, String patientId, Set<String> categories,
                                      String versionRaw) {
        Set<String> requested = categories == null ? Set.of() : new LinkedHashSet<>(categories);
        authorizationService.validate(grantId, patientId, requested, Instant.now());

        long version = parseVersion(versionRaw);
        PatientRecord record = ingestionService.getPatient(patientId);
        VersionSnapshot snapshot = record == null ? null : record.versionSnapshot(version);
        if (snapshot == null) {
            // 版本不存在（含超出当前范围、或属于其他患者的版本号）：可区分拒绝，不泄露存在性
            throw new ExportException(ExportErrorReason.VERSION_NOT_FOUND,
                    "患者不存在版本 " + version + "（当前共 "
                            + (record == null ? 0 : record.version()) + " 个版本）");
        }
        List<Observation> effective = snapshot.effectiveFingerprints().stream()
                .map(record::observation)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<DeidentifiedObservation> rows = deidentifyRows(effective, requested);
        return buildBundle(pseudonym(patientId), snapshot.version(), requested, rows);
    }

    /**
     * 版本历史查询：版本号、变化时间、相对上一版的变化清单。
     * 走同一套授权校验；视图不含患者标识、来源系统、消息控制号。
     */
    public List<VersionView> listVersions(String grantId, String patientId) {
        authorizationService.validate(grantId, patientId, Set.of(), Instant.now());
        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            return List.of();
        }
        return record.versions().stream()
                .map(v -> new VersionView(v.version(), v.changedAt(), v.changes()))
                .toList();
    }

    private long parseVersion(String versionRaw) {
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new ExportException(ExportErrorReason.INVALID_VERSION, "版本号缺失");
        }
        final long version;
        try {
            version = Long.parseLong(versionRaw.trim());
        } catch (NumberFormatException e) {
            throw new ExportException(ExportErrorReason.INVALID_VERSION,
                    "版本号必须为正整数: " + versionRaw);
        }
        if (version < 1) {
            throw new ExportException(ExportErrorReason.INVALID_VERSION,
                    "版本号必须为正整数: " + versionRaw);
        }
        return version;
    }

    /**
     * 不做授权校验的去标识化（仅限服务内部/测试直接使用；REST 路径必须走 export）。
     * 导出的是患者当前有效观测集合：被更正掉、被撤回、被冲突裁决掉的值不在其中。
     */
    public List<DeidentifiedObservation> deidentify(String patientId, Set<String> categories) {
        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            return List.of();
        }
        return deidentifyRows(record.effectiveObservations(), categories);
    }

    private List<DeidentifiedObservation> deidentifyRows(List<Observation> observations,
                                                         Set<String> categories) {
        Set<String> allowed = categories == null ? Set.of() : categories;
        List<DeidentifiedObservation> rows = new ArrayList<>();
        for (Observation obs : observations) {
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
