package com.github.h3yow.clinicaldatanormalizerjava.normalize;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 观测代码映射：来源代码系统 + 本地代码 -> LOINC。
 * 未命中返回 empty，由调用方按 UNMAPPABLE_CODE 拒绝，不得默认兜底。
 *
 * 映射策略（详见 README）：
 * - LN / LOINC：已是目标代码系统，原样通过；
 * - 本地系统（L、LOCAL、99LAB 等）：查内置映射表；
 * - 表外代码一律拒绝，保证映射可审计、可复现。
 */
@Component
public class CodeMapper {

    /** key: 大写代码系统 + "|" + 本地代码 */
    private static final Map<String, String> TABLE = Map.ofEntries(
            // 血糖
            Map.entry("L|GLU", "2345-7"),
            Map.entry("99LAB|GLU", "2345-7"),
            Map.entry("L|GLUCOSE", "2345-7"),
            // 收缩压 / 舒张压
            Map.entry("L|SBP", "8480-6"),
            Map.entry("L|DBP", "8462-4"),
            Map.entry("99LAB|SBP", "8480-6"),
            Map.entry("99LAB|DBP", "8462-4"),
            // 心率
            Map.entry("L|HR", "8867-4"),
            Map.entry("99LAB|HR", "8867-4"),
            // 体温
            Map.entry("L|TEMP", "8310-5"),
            Map.entry("99LAB|TEMP", "8310-5"),
            // 血红蛋白
            Map.entry("L|HGB", "718-7"),
            Map.entry("99LAB|HGB", "718-7")
    );

    public Optional<String> toLoinc(String codeSystem, String localCode) {
        if (localCode == null || localCode.isBlank()) {
            return Optional.empty();
        }
        String sys = codeSystem == null ? "" : codeSystem.trim().toUpperCase();
        String code = localCode.trim();
        if (sys.equals("LN") || sys.equals("LOINC")) {
            return Optional.of(code);
        }
        return Optional.ofNullable(TABLE.get(sys + "|" + code.toUpperCase()));
    }
}
