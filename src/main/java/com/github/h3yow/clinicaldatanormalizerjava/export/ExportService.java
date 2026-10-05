package com.github.h3yow.clinicaldatanormalizerjava.export;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationGrant;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.PatientRecord;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.VersionChange;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.VersionSnapshot;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 去标识化导出服务（当前版本 + 历史版本复盘）。
 *
 * <p>最小必要披露边界：
 * <ul>
 *   <li>导出前必须通过 {@link AuthorizationService} 校验：授权存在、未过期、
 *       覆盖目标患者、请求大类不超范围，任一不满足即拒绝且不产生数据。
 *       历史版本导出与版本清单查询走同一套校验。</li>
 *   <li>输出不含任何直接标识符：无患者 ID、无来源系统、无消息控制号、无内容指纹；
 *       患者身份替换为带服务盐的确定性伪名（SHA-256），同一患者跨次导出一致，
 *       但不可逆推回原 ID。版本元数据同样只含去标识化字段。</li>
 *   <li>时间精度降低到年份（effectiveYear），避免日期级重标识。</li>
 *   <li>仅导出授权大类内的观测；完全相同的去标识化行合并为一行。</li>
 *   <li>只导出"当前有效集合"：被更正掉或被撤回的值不会出现在当前版本导出中。</li>
 * </ul>
 *
 * <p>版本语义：患者的当前有效观测集合每真实变化一次产生一个版本（从 1 开始）。
 * 指定历史版本导出时，取该版本产生时固定的不可变快照，之后投递的新数据不影响结果。
 * 指定不存在的版本（&lt;1、&gt;当前版本、或不属于该患者）以
 * {@link VersionNotFoundException}（VERSION_NOT_FOUND）区分拒绝。
 *
 * <p>可复现性：数据版本与授权范围（大类清单）相同时，观测集合、伪名、摘要 digest
 * 完全一致——无论当前版本还是历史版本。digest 为对规范化排序后内容的 SHA-256。
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

    /** 导出当前版本（等价于 {@link #export(String, String, Set, Long)} 传 null 版本）。 */
    public ExportBundle export(String grantId, String patientId, Set<String> categories) {
        return export(grantId, patientId, categories, null);
    }

    /**
     * 去标识化导出。
     *
     * @param version 指定历史版本号；null 表示当前版本。
     *                版本不存在时抛 {@link VersionNotFoundException}。
     */
    public ExportBundle export(String grantId, String patientId, Set<String> categories, Long version) {
        Set<String> requested = categories == null ? Set.of() : new LinkedHashSet<>(categories);
        authorizationService.validate(grantId, patientId, requested, Instant.now());

        PatientRecord record = ingestionService.getPatient(patientId);
        if (version == null) {
            if (record == null) {
                // 未入库的患者不泄露其存在性：返回空集
                return buildBundle(pseudonym(patientId), 0, requested, List.of());
            }
            List<DeidentifiedObservation> rows = deidentify(record.effectiveObservations(), requested);
            return buildBundle(pseudonym(patientId), record.version(), requested, rows);
        }

        // 历史版本复盘：快照在版本产生时固定，不受后续投递影响
        VersionSnapshot snapshot = record == null ? null : record.snapshot(version);
        if (snapshot == null) {
            throw new VersionNotFoundException("患者 " + patientId + " 不存在版本 " + version
                    + "（当前版本 " + (record == null ? 0 : record.version()) + "）");
        }
        List<DeidentifiedObservation> rows = deidentify(snapshot.effectiveSet(), requested);
        return buildBundle(pseudonym(patientId), snapshot.version(), requested, rows);
    }

    /**
     * 查询患者的版本历史（去标识化元数据：版本号、变化时间、变更清单）。
     * 走与导出相同的授权校验；变更清单按授权大类过滤（最小必要）。
     * 未入库患者返回空清单，不泄露存在性。
     */
    public List<VersionInfo> listVersions(String grantId, String patientId) {
        AuthorizationGrant grant = authorizationService.validate(
                grantId, patientId, Set.of(), Instant.now());
        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            return List.of();
        }
        List<VersionInfo> result = new ArrayList<>();
        for (VersionSnapshot snapshot : record.versions()) {
            List<VersionChangeView> views = new ArrayList<>();
            for (VersionChange change : snapshot.changes()) {
                if (!grant.categories().contains(change.category())) {
                    continue; // 最小必要：不暴露授权范围外指标的变更痕迹
                }
                views.add(new VersionChangeView(
                        change.type().name(),
                        deidentifyOne(record, change.oldFingerprint()),
                        deidentifyOne(record, change.newFingerprint())));
            }
            result.add(new VersionInfo(snapshot.version(), snapshot.changedAt(), views));
        }
        return result;
    }

    /**
     * 不做授权校验的去标识化（仅限服务内部/测试直接使用；REST 路径必须走 export）。
     * 只取当前有效集合：被更正/被撤回的值不在其中。
     */
    public List<DeidentifiedObservation> deidentify(String patientId, Set<String> categories) {
        PatientRecord record = ingestionService.getPatient(patientId);
        if (record == null) {
            return List.of();
        }
        return deidentify(record.effectiveObservations(),
                categories == null ? Set.of() : categories);
    }

    private List<DeidentifiedObservation> deidentify(List<Observation> source, Set<String> allowed) {
        List<DeidentifiedObservation> rows = new ArrayList<>();
        for (Observation obs : source) {
            if (!allowed.contains(obs.category())) {
                continue;
            }
            rows.add(toDeidentified(obs));
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

    private DeidentifiedObservation deidentifyOne(PatientRecord record, String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        Observation obs = record.observation(fingerprint);
        return obs == null ? null : toDeidentified(obs);
    }

    private DeidentifiedObservation toDeidentified(Observation obs) {
        int year = obs.effectiveTime().atZone(ZoneOffset.UTC).getYear();
        return new DeidentifiedObservation(
                obs.category(), obs.code(), obs.displayName(),
                obs.value(), obs.unit(), year);
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
