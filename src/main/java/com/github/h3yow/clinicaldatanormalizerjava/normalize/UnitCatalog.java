package com.github.h3yow.clinicaldatanormalizerjava.normalize;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单位目录：识别来源单位并换算到统一 UCUM 规范单位。
 *
 * <p>映射策略：
 * <ul>
 *   <li>每个指标（统一编码）有唯一规范单位：
 *       <ul>
 *         <li>8867-4 心率 → bpm（/min）</li>
 *         <li>8310-5 体温 → Cel（摄氏度）</li>
 *         <li>8480-6 收缩压 → mm[Hg]</li>
 *         <li>2345-7 血糖 → mg/dL</li>
 *       </ul>
 *   </li>
 *   <li>来源单位按 UCUM 常用写法及其别名（含部分本地写法）收录；
 *       未收录的单位返回 null，由归一化层以 UNKNOWN_UNIT 拒绝，绝不猜测。</li>
 *   <li>换算采用 {@code 规范值 = value * factor + offset}：
 *       factor 处理倍率（如 mg/L、kPa、mmol/L），offset 处理零点平移（华氏度）。</li>
 * </ul>
 */
@Component
public class UnitCatalog {

    /** 单位换算：canonical = value * factor + offset。 */
    public record UnitConversion(String canonicalUnit, double factor, double offset) {
        public double convert(double value) {
            return value * factor + offset;
        }
    }

    /** 指标 → 规范单位。 */
    private static final Map<String, String> CANONICAL_UNITS = Map.of(
            "8867-4", "/min",
            "8310-5", "Cel",
            "8480-6", "mm[Hg]",
            "2345-7", "mg/dL"
    );

    /** (规范单位, 来源单位写法小写) → 换算。 */
    private final Map<String, UnitConversion> conversions = new LinkedHashMap<>();

    public UnitCatalog() {
        // 心率
        define("/min", "bpm", 1.0, 0.0);
        define("/min", "/min", 1.0, 0.0);
        define("/min", "beats/min", 1.0, 0.0);
        // 体温
        define("Cel", "Cel", 1.0, 0.0);
        define("Cel", "degC", 1.0, 0.0);
        define("Cel", "C", 1.0, 0.0);
        // 华氏度 -> 摄氏度
        define("Cel", "degF", 5.0 / 9.0, -32.0 * 5.0 / 9.0);
        define("Cel", "F", 5.0 / 9.0, -32.0 * 5.0 / 9.0);
        // 血压
        define("mm[Hg]", "mm[Hg]", 1.0, 0.0);
        define("mm[Hg]", "mmHg", 1.0, 0.0);
        define("mm[Hg]", "torr", 1.0, 0.0);
        // kPa -> mmHg，1 kPa = 7.500616827 mmHg
        define("mm[Hg]", "kPa", 7.500616827, 0.0);
        // 血糖
        define("mg/dL", "mg/dL", 1.0, 0.0);
        define("mg/dL", "mg/100ml", 1.0, 0.0);
        define("mg/dL", "mg/L", 0.1, 0.0);
        // mmol/L 葡萄糖 -> mg/dL：乘以 18.0156（葡萄糖摩尔质量 180.156 g/mol）
        define("mg/dL", "mmol/L", 18.0156, 0.0);
        define("mg/dL", "mmol/l", 18.0156, 0.0);
    }

    private void define(String canonicalUnit, String sourceUnit, double factor, double offset) {
        conversions.put(canonicalUnit + '|' + sourceUnit.trim().toLowerCase(),
                new UnitConversion(canonicalUnit, factor, offset));
    }

    public String canonicalUnit(String metricCode) {
        return CANONICAL_UNITS.get(metricCode);
    }

    /**
     * 按指标解析来源单位。
     *
     * @return 换算定义；单位未收录或该指标没有规范单位时返回 null（调用方按 UNKNOWN_UNIT 拒绝）
     */
    public UnitConversion resolve(String metricCode, String sourceUnit) {
        String canonical = CANONICAL_UNITS.get(metricCode);
        if (canonical == null || sourceUnit == null || sourceUnit.isBlank()) {
            return null;
        }
        return conversions.get(canonical + '|' + sourceUnit.trim().toLowerCase());
    }
}
