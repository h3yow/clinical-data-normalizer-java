package com.github.h3yow.clinicaldatanormalizerjava.normalize;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编码目录：把来源系统的观测代码（LOINC 本地码 / 系统私有码）归一到统一指标。
 *
 * <p>策略：
 * <ul>
 *   <li>统一指标使用 LOINC 编码；目录以 (来源系统, 来源编码, 编码体系) 为键。</li>
 *   <li>来源系统为 "*" 表示任意来源（用于官方 LOINC 码）；
 *       来源特定映射优先于通配映射。</li>
 *   <li>目录为内置固定版本，未收录的编码一律以 UNKNOWN_OBSERVATION_CODE 拒绝，
 *       不做猜测式映射。</li>
 * </ul>
 */
@Component
public class CodeCatalog {

    /** 统一指标定义。 */
    public record Metric(String code, String displayName, String category) {
    }

    /** 来源映射条目。 */
    public record SourceCode(String sourceSystem, String code, String codeSystem) {
    }

    private final Map<SourceCode, Metric> mappings = new LinkedHashMap<>();

    public CodeCatalog() {
        // 心率：LOINC 8867-4
        put(new SourceCode("*", "8867-4", "LN"), new Metric("8867-4", "Heart rate", "vital_signs"));
        put(new SourceCode("HIS_A", "HR", "LOCAL"), new Metric("8867-4", "Heart rate", "vital_signs"));
        // 体温：LOINC 8310-5
        put(new SourceCode("*", "8310-5", "LN"), new Metric("8310-5", "Body temperature", "vital_signs"));
        put(new SourceCode("HIS_A", "TEMP", "LOCAL"), new Metric("8310-5", "Body temperature", "vital_signs"));
        // 血糖：LOINC 2345-7
        put(new SourceCode("*", "2345-7", "LN"), new Metric("2345-7", "Glucose in Serum/Plasma", "laboratory"));
        put(new SourceCode("LAB_X", "GLU", "LOCAL"), new Metric("2345-7", "Glucose in Serum/Plasma", "laboratory"));
        // 收缩压：LOINC 8480-6
        put(new SourceCode("*", "8480-6", "LN"), new Metric("8480-6", "Systolic blood pressure", "vital_signs"));
        put(new SourceCode("HIS_A", "SBP", "LOCAL"), new Metric("8480-6", "Systolic blood pressure", "vital_signs"));
    }

    private void put(SourceCode sourceCode, Metric metric) {
        mappings.put(sourceCode, metric);
    }

    /**
     * 查找统一指标。先匹配来源特定条目，再回退通配来源 "*"。
     *
     * @return 统一指标；未收录返回 null（调用方按 UNKNOWN_OBSERVATION_CODE 拒绝）
     */
    public Metric resolve(String sourceSystem, String code, String codeSystem) {
        Metric specific = mappings.get(new SourceCode(sourceSystem, code, codeSystem));
        if (specific != null) {
            return specific;
        }
        return mappings.get(new SourceCode("*", code, codeSystem));
    }

    public int size() {
        return mappings.size();
    }
}
