package com.github.h3yow.clinicaldatanormalizerjava.normalize;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Segment;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.RejectionReason;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把解析后的 HL7 消息归一化为统一观测模型。
 *
 * 规则：
 * - 仅支持 ORU^R01（观测报告），其他消息类型按 UNSUPPORTED_MESSAGE 拒绝；
 * - 必需字段：MSH-3(来源)、MSH-10(消息控制ID)、PID-3(患者ID)、
 *   每个 OBX 的 OBX-1(set id)/OBX-2(值类型)/OBX-3(代码)/OBX-5(值)/
 *   OBX-6(单位)/OBX-14(时间)；缺失按 MALFORMED_STRUCTURE 拒绝；
 * - 值类型仅接受 NM（数值），其他类型按 INVALID_VALUE 拒绝；
 * - 代码经 CodeMapper 映射、单位经 UnitNormalizer 换算，
 *   失败分别按 UNMAPPABLE_CODE / UNKNOWN_UNIT 拒绝；
 * - 任何一步失败整条消息拒绝，不用默认值兜底。
 */
@Service
public class NormalizationService {

    private final CodeMapper codeMapper;
    private final UnitNormalizer unitNormalizer;

    public NormalizationService(CodeMapper codeMapper, UnitNormalizer unitNormalizer) {
        this.codeMapper = codeMapper;
        this.unitNormalizer = unitNormalizer;
    }

    public List<Observation> normalize(Hl7Message message) {
        Hl7Segment msh = message.first("MSH");
        String msgType = msh.text(9);
        if (!msgType.startsWith("ORU") ) {
            throw new Hl7ParseException(RejectionReason.UNSUPPORTED_MESSAGE,
                    "unsupported message type: " + msgType);
        }
        String sourceId = required(msh.text(3), "MSH-3 sending application");
        String messageId = required(msh.text(10), "MSH-10 message control id");

        Hl7Segment pid = message.first("PID");
        if (pid == null) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                    "missing PID segment");
        }
        String patientId = required(pid.field(3).component(1), "PID-3 patient id");

        List<Hl7Segment> obxList = message.segments("OBX");
        if (obxList.isEmpty()) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                    "message contains no OBX segment");
        }

        List<Observation> out = new ArrayList<>();
        for (Hl7Segment obx : obxList) {
            out.add(normalizeObx(obx, patientId, sourceId, messageId));
        }
        return out;
    }

    private Observation normalizeObx(Hl7Segment obx, String patientId,
                                     String sourceId, String messageId) {
        String setId = required(obx.text(1), "OBX-1 set id");
        String valueType = required(obx.text(2), "OBX-2 value type");
        if (!valueType.equals("NM")) {
            throw new Hl7ParseException(RejectionReason.INVALID_VALUE,
                    "OBX-" + setId + ": unsupported value type " + valueType + " (only NM)");
        }
        String localCode = required(obx.field(3).component(1), "OBX-3 observation code");
        String codeSystem = obx.field(3).component(3);
        String loinc = codeMapper.toLoinc(codeSystem, localCode)
                .orElseThrow(() -> new Hl7ParseException(RejectionReason.UNMAPPABLE_CODE,
                        "OBX-" + setId + ": cannot map code " + codeSystem + "|" + localCode));

        String rawValue = required(obx.text(5), "OBX-5 observation value");
        BigDecimal value;
        try {
            value = new BigDecimal(rawValue.trim());
        } catch (NumberFormatException e) {
            throw new Hl7ParseException(RejectionReason.INVALID_VALUE,
                    "OBX-" + setId + ": non-numeric value '" + rawValue + "'");
        }

        String unit = required(obx.text(6), "OBX-6 unit");
        UnitNormalizer.Converted converted = unitNormalizer.toCanonical(loinc, value, unit)
                .orElseThrow(() -> new Hl7ParseException(RejectionReason.UNKNOWN_UNIT,
                        "OBX-" + setId + ": unknown/incompatible unit '" + unit
                                + "' for " + loinc));

        Instant time = parseTime(required(obx.text(14), "OBX-14 observation time"), setId);

        return new Observation(patientId, loinc, converted.value(), converted.canonicalUnit(),
                time, sourceId, messageId, messageId + "#OBX-" + setId);
    }

    /** 支持 YYYYMMDDHHMMSS[+-ZZZZ]，无时区按 UTC 处理（确定性要求） */
    static Instant parseTime(String ts, String setId) {
        String t = ts.trim();
        try {
            if (t.length() == 14) {
                LocalDateTime ldt = LocalDateTime.parse(t,
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                return ldt.toInstant(ZoneOffset.UTC);
            }
            if (t.length() == 19) {
                OffsetDateTime odt = OffsetDateTime.parse(t,
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ"));
                return odt.toInstant();
            }
        } catch (DateTimeParseException e) {
            // fall through
        }
        throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                "OBX-" + setId + ": bad timestamp '" + ts + "'");
    }

    private static String required(String v, String fieldName) {
        if (v == null || v.isBlank()) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                    "missing required field: " + fieldName);
        }
        return v.trim();
    }
}
