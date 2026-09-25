package com.github.h3yow.clinicaldatanormalizerjava.export;

import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * 去标识化导出。
 *
 * 边界（详见 README）：
 * - 患者 ID 用 HMAC-SHA256(密钥, patientId) 假名化，截断 16 字节，
 *   同一患者跨导出可关联但无法反推原始 ID；
 * - 观测时间泛化为日期（UTC），去除精确时间戳；
 * - 丢弃 messageId / observationKey / sourceId 等准标识符；
 * - 记录按字典序排序后计算 SHA-256 摘要：digest = SHA-256(
 *   scopeKey | dataVersion | 每行记录 )，因此同一数据版本 +
 *   同一授权范围的导出摘要必然可复现。
 */
@Component
public class DeidExporter {

    private final byte[] hmacKey;

    public DeidExporter(@Value("${deid.hmac-key:local-dev-only-key}") String hmacKey) {
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    public ExportResult export(List<Observation> observations, String scopeKey,
                               long dataVersion) {
        List<String> records = observations.stream()
                .map(this::toRecord)
                .sorted()
                .toList();
        String digest = sha256(scopeKey + "|v" + dataVersion + "|" + String.join("\n", records));
        return new ExportResult(records, digest, "v" + dataVersion);
    }

    /** 单行记录：假名|LOINC|值|单位|日期 —— 不含任何直接标识符 */
    private String toRecord(Observation o) {
        String pseudonym = pseudonymize(o.patientId());
        String date = DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(ZoneOffset.UTC)
                .format(o.effectiveTime());
        return pseudonym + "|" + o.code() + "|" + o.value().toPlainString()
                + "|" + o.unit() + "|" + date;
    }

    private String pseudonymize(String patientId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] out = mac.doFinal(patientId.getBytes(StandardCharsets.UTF_8));
            return "P" + HexFormat.of().formatHex(out, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String sha256(String s) {
        try {
            byte[] out = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
