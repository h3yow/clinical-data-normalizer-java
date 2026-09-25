package com.github.h3yow.clinicaldatanormalizerjava.normalize;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message.Field;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message.Segment;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 把一条解析后的 HL7 v2 ORU^R01 消息归一化为统一 {@link Observation} 列表。
 *
 * <p>硬性规则（任何不满足都以可区分的 {@link NormalizationErrorReason} 拒绝，不兜底）：
 * <ul>
 *   <li>仅接受 MSH-9 = ORU^R01；MSH-10、MSH-3、MSH-7 必填且合法。</li>
 *   <li>必须存在 PID（PID-3 非空）、OBR、至少一个 OBX。</li>
 *   <li>OBX-2 必须为 NM；OBX-11 仅接受 F（Final）/C（Corrected）。</li>
 *   <li>OBX-3 必须命中编码目录；OBX-6 必须命中单位目录并换算到规范单位。</li>
 *   <li>OBX-5 的每个重复各自产生一条观测；空值、NaN/Infinity、越界数值拒绝。</li>
 *   <li>生效时间取 OBX-14，缺省回退 OBR-7，再回退 MSH-7；精度至少到分钟。</li>
 * </ul>
 */
@Component
public class ObservationNormalizer {

    private final CodeCatalog codeCatalog;
    private final UnitCatalog unitCatalog;

    public ObservationNormalizer(CodeCatalog codeCatalog, UnitCatalog unitCatalog) {
        this.codeCatalog = codeCatalog;
        this.unitCatalog = unitCatalog;
    }

    public List<Observation> normalize(Hl7Message message) {
        Segment msh = message.segment("MSH");
        if (msh == null) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_MSH, "缺少 MSH 段");
        }

        String messageType = msh.get(9, 0, 0);
        String triggerEvent = msh.get(9, 0, 1);
        if (messageType == null || messageType.isBlank() || triggerEvent == null || triggerEvent.isBlank()) {
            throw new NormalizationException(NormalizationErrorReason.MALFORMED_MESSAGE_TYPE,
                    "MSH-9 必须同时给出消息类型与触发事件（ORU^R01）");
        }
        if (!"ORU".equals(messageType.trim()) || !"R01".equals(triggerEvent.trim())) {
            throw new NormalizationException(NormalizationErrorReason.UNSUPPORTED_MESSAGE_TYPE,
                    "仅接受 ORU^R01，实际: " + messageType + "^" + triggerEvent);
        }

        String controlId = msh.get(10);
        if (controlId == null || controlId.isBlank()) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_MESSAGE_CONTROL_ID,
                    "MSH-10 消息控制号缺失");
        }
        String sourceSystem = msh.get(3, 0, 0);
        if (sourceSystem == null || sourceSystem.isBlank()) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_SENDING_APPLICATION,
                    "MSH-3 发送应用缺失，来源不可追溯");
        }
        Instant messageTime = parseDtm(msh.get(7), NormalizationErrorReason.MISSING_OR_INVALID_MESSAGE_TIME);

        Segment pid = message.segment("PID");
        if (pid == null) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_PID, "缺少 PID 段");
        }
        String patientId = firstNonBlankRepetition(pid, 3);
        if (patientId == null) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_PATIENT_ID,
                    "PID-3 患者标识缺失");
        }

        Segment obr = message.segment("OBR");
        if (obr == null) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_OBR, "缺少 OBR 段");
        }
        Instant obrTime = obr.hasField(7) && !isBlank(obr.get(7))
                ? parseDtm(obr.get(7), NormalizationErrorReason.INVALID_DATETIME)
                : null;

        List<Segment> obxSegments = message.segments("OBX");
        if (obxSegments.isEmpty()) {
            throw new NormalizationException(NormalizationErrorReason.MISSING_OBX, "消息中没有 OBX 段");
        }

        List<Observation> observations = new ArrayList<>();
        for (int idx = 0; idx < obxSegments.size(); idx++) {
            Segment obx = obxSegments.get(idx);
            int observationId = parseObservationId(obx, idx + 1);

            String valueType = obx.get(2);
            if (valueType == null || !"NM".equals(valueType.trim())) {
                throw new NormalizationException(NormalizationErrorReason.UNSUPPORTED_OBSERVATION_VALUE_TYPE,
                        "OBX-2 仅接受 NM，实际: " + valueType + "（OBX#" + observationId + "）");
            }

            String status = obx.get(11);
            if (status == null || (!"F".equals(status.trim()) && !"C".equals(status.trim()))) {
                throw new NormalizationException(NormalizationErrorReason.INVALID_OBSERVATION_STATUS,
                        "OBX-11 仅接受 F/C，实际: " + status + "（OBX#" + observationId + "）");
            }

            String sourceCode = obx.get(3, 0, 0);
            if (isBlank(sourceCode)) {
                throw new NormalizationException(NormalizationErrorReason.MISSING_OBSERVATION_CODE,
                        "OBX-3 观测标识符缺失（OBX#" + observationId + "）");
            }
            String codeSystem = obx.get(3, 0, 2);
            if (isBlank(codeSystem)) {
                codeSystem = "LN";
            }
            CodeCatalog.Metric metric = codeCatalog.resolve(sourceSystem.trim(), sourceCode.trim(), codeSystem.trim());
            if (metric == null) {
                throw new NormalizationException(NormalizationErrorReason.UNKNOWN_OBSERVATION_CODE,
                        "未收录的观测代码: " + codeSystem + ":" + sourceCode
                                + "（来源 " + sourceSystem + "，OBX#" + observationId + "）");
            }

            Field valueField = obx.field(5);
            if (valueField == null || valueField.repetitions().isEmpty()) {
                throw new NormalizationException(NormalizationErrorReason.MISSING_OBSERVATION_VALUE,
                        "OBX-5 观测值缺失（" + metric.code() + "）");
            }

            Field unitField = obx.field(6);
            String sourceUnit = unitField == null ? null : unitField.repetitions().get(0).components()
                    .get(0).subcomponents().get(0);
            if (isBlank(sourceUnit)) {
                throw new NormalizationException(NormalizationErrorReason.MISSING_UNIT,
                        "OBX-6 单位缺失（" + metric.code() + "）");
            }
            // OBX-6 若出现重复单位，必须全部一致（同一观测值序列不得混单位）
            if (unitField.repetitionCount() > 1) {
                for (var rep : unitField.repetitions()) {
                    String other = rep.components().get(0).subcomponents().get(0);
                    if (!sourceUnit.trim().equalsIgnoreCase(other == null ? "" : other.trim())) {
                        throw new NormalizationException(
                                NormalizationErrorReason.INCONSISTENT_UNIT_ACROSS_REPETITIONS,
                                "OBX-6 重复单位不一致: " + sourceUnit + " vs " + other);
                    }
                }
            }
            UnitCatalog.UnitConversion conversion = unitCatalog.resolve(metric.code(), sourceUnit);
            if (conversion == null) {
                throw new NormalizationException(NormalizationErrorReason.UNKNOWN_UNIT,
                        "无法识别的单位: '" + sourceUnit + "'（指标 " + metric.code() + "）");
            }

            Instant effectiveTime;
            String obx14 = obx.get(14);
            if (!isBlank(obx14)) {
                effectiveTime = parseDtm(obx14, NormalizationErrorReason.INVALID_DATETIME);
            } else if (obrTime != null) {
                effectiveTime = obrTime;
            } else {
                effectiveTime = messageTime;
            }

            for (int rep = 0; rep < valueField.repetitions().size(); rep++) {
                String rawValue = valueField.repetitions().get(rep).components().get(0).subcomponents().get(0);
                if (isBlank(rawValue)) {
                    throw new NormalizationException(NormalizationErrorReason.MISSING_OBSERVATION_VALUE,
                            "OBX-5 第 " + (rep + 1) + " 个重复值为空（" + metric.code() + "）");
                }
                double numericValue = parseNumeric(rawValue.trim(), metric.code(), observationId);
                double canonicalValue = conversion.convert(numericValue);
                if (!Double.isFinite(canonicalValue)) {
                    throw new NormalizationException(NormalizationErrorReason.VALUE_OUT_OF_RANGE,
                            "换算后数值越界: " + rawValue + " " + sourceUnit + "（" + metric.code() + "）");
                }
                String fingerprint = fingerprint(patientId, metric.code(), effectiveTime,
                        canonicalValue, conversion.canonicalUnit());
                observations.add(new Observation(
                        patientId,
                        metric.category(),
                        metric.code(),
                        metric.displayName(),
                        canonicalValue,
                        conversion.canonicalUnit(),
                        effectiveTime,
                        sourceSystem.trim(),
                        controlId.trim(),
                        observationId,
                        fingerprint));
            }
        }
        return observations;
    }

    private int parseObservationId(Segment obx, int index) {
        String raw = obx.get(1);
        if (isBlank(raw)) {
            return index;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return index;
        }
    }

    private double parseNumeric(String raw, String code, int observationId) {
        String upper = raw.toUpperCase();
        if (upper.contains("NAN") || upper.contains("INFINITY")) {
            throw new NormalizationException(NormalizationErrorReason.NON_NUMERIC_OBSERVATION_VALUE,
                    "观测值不是有限实数: " + raw + "（" + code + "，OBX#" + observationId + "）");
        }
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            // 指数超界（如 1E400）JDK 返回 Infinity 或抛 NumberFormatException，
            // 均视为数值越界，与"非数字"区分
            if (e.getMessage() != null && e.getMessage().contains("exceeds range")) {
                throw new NormalizationException(NormalizationErrorReason.VALUE_OUT_OF_RANGE,
                        "观测值超出可表达范围: " + raw + "（" + code + "，OBX#" + observationId + "）");
            }
            throw new NormalizationException(NormalizationErrorReason.NON_NUMERIC_OBSERVATION_VALUE,
                    "观测值无法解析: " + raw + "（" + code + "，OBX#" + observationId + "）");
        }
        if (Double.isNaN(value)) {
            throw new NormalizationException(NormalizationErrorReason.NON_NUMERIC_OBSERVATION_VALUE,
                    "观测值不是有限实数: " + raw + "（" + code + "，OBX#" + observationId + "）");
        }
        if (Double.isInfinite(value)) {
            throw new NormalizationException(NormalizationErrorReason.VALUE_OUT_OF_RANGE,
                    "观测值超出可表达范围: " + raw + "（" + code + "，OBX#" + observationId + "）");
        }
        return value;
    }

    /**
     * 解析 HL7 v2 DTM：YYYYMMDDHHMMSS[.ffff][+/-ZZZZ]。
     * 观测时间精度至少到分钟（10 位）；不带时区时按 UTC 处理（文档化约定）。
     */
    private Instant parseDtm(String raw, NormalizationErrorReason onMissing) {
        if (raw == null || raw.isBlank()) {
            throw new NormalizationException(onMissing, "时间字段为空");
        }
        String s = raw.trim();
        String zonePart = "";
        int tzAt = -1;
        for (int i = 8; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' || c == '-') {
                tzAt = i;
                break;
            }
        }
        ZoneOffset offset = ZoneOffset.UTC;
        if (tzAt >= 0) {
            zonePart = s.substring(tzAt);
            s = s.substring(0, tzAt);
            offset = parseOffset(zonePart);
        }
        int dot = s.indexOf('.');
        String digits;
        if (dot >= 0) {
            digits = s.substring(0, dot);
            String fraction = s.substring(dot + 1);
            if (!fraction.matches("\\d{1,4}") || !digits.matches("\\d{12}")) {
                throw new NormalizationException(NormalizationErrorReason.INVALID_DATETIME,
                        "时间格式非法: " + raw);
            }
        } else {
            digits = s;
        }
        if (!digits.matches("\\d{10}|\\d{12}|\\d{14}")) {
            NormalizationErrorReason reason = onMissing == NormalizationErrorReason.MISSING_OR_INVALID_MESSAGE_TIME
                    ? onMissing : NormalizationErrorReason.INVALID_DATETIME;
            throw new NormalizationException(reason,
                    "时间须至少精确到分钟（YYYYMMDDHHMM）: " + raw);
        }
        try {
            int year = Integer.parseInt(digits.substring(0, 4));
            int month = Integer.parseInt(digits.substring(4, 6));
            int day = Integer.parseInt(digits.substring(6, 8));
            int hour = Integer.parseInt(digits.substring(8, 10));
            int minute = digits.length() >= 12 ? Integer.parseInt(digits.substring(10, 12)) : 0;
            int second = digits.length() >= 14 ? Integer.parseInt(digits.substring(12, 14)) : 0;
            return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(offset);
        } catch (RuntimeException e) {
            throw new NormalizationException(NormalizationErrorReason.INVALID_DATETIME,
                    "时间字段无法解析: " + raw + "（" + e.getMessage() + "）");
        }
    }

    private ZoneOffset parseOffset(String zonePart) {
        if (!zonePart.matches("[+-]\\d{4}")) {
            throw new NormalizationException(NormalizationErrorReason.INVALID_DATETIME,
                    "非法时区偏移: " + zonePart);
        }
        int hours = Integer.parseInt(zonePart.substring(1, 3));
        int minutes = Integer.parseInt(zonePart.substring(3, 5));
        int total = hours * 60 + minutes;
        return ZoneOffset.ofTotalSeconds((zonePart.charAt(0) == '-' ? -total : total) * 60);
    }

    private String firstNonBlankRepetition(Segment segment, int fieldNo) {
        Field f = segment.field(fieldNo);
        if (f == null) {
            return null;
        }
        for (var rep : f.repetitions()) {
            if (!rep.components().isEmpty()) {
                String v = rep.components().get(0).value();
                if (!isBlank(v)) {
                    return v.trim();
                }
            }
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String fingerprint(String patientId, String code, Instant time, double value, String unit) {
        String canonical = patientId + '|' + code + '|' + time.getEpochSecond()
                + '|' + Double.toString(value) + '|' + unit;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
