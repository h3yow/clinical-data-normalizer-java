package com.github.h3yow.clinicaldatanormalizerjava.normalize;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * UCUM 单位归一化：把来源单位换算到每个指标（LOINC）的规范单位。
 *
 * 策略：
 * - 每个指标声明一个规范单位（如血糖 -> mmol/L）；
 * - 来源单位必须在 UCUM 白名单内，且与规范单位同量纲才可换算；
 * - 换算系数用精确 BigDecimal，结果保留 6 位小数（HALF_UP），
 *   保证同一输入永远得到同一输出（可复现）；
 * - 未知单位 / 量纲不符返回 empty，由调用方按 UNKNOWN_UNIT 拒绝。
 */
@Component
public class UnitNormalizer {

    public record Converted(BigDecimal value, String canonicalUnit) {
    }

    /** 指标 -> 规范单位 */
    private static final Map<String, String> CANONICAL_UNIT = Map.of(
            "2345-7", "mmol/L",   // 血糖
            "8480-6", "mm[Hg]",   // 收缩压
            "8462-4", "mm[Hg]",   // 舒张压
            "8867-4", "/min",     // 心率
            "8310-5", "Cel",      // 体温
            "718-7", "g/dL"       // 血红蛋白
    );

    /** 同量纲换算组：单位 -> 相对组内基准单位的乘数（基准单位乘数为 1） */
    private static final Map<String, BigDecimal> MOLAR = Map.of(
            "mmol/L", BigDecimal.ONE,
            "mmol/l", BigDecimal.ONE,
            "umol/L", new BigDecimal("0.001")
    );
    private static final Map<String, BigDecimal> PRESSURE = Map.of(
            "mm[Hg]", BigDecimal.ONE,
            "mmHg", BigDecimal.ONE,
            "kPa", new BigDecimal("7.50061683")
    );
    private static final Map<String, BigDecimal> RATE = Map.of(
            "/min", BigDecimal.ONE,
            "{beats}/min", BigDecimal.ONE,
            "/h", new BigDecimal("60")
    );
    private static final Map<String, BigDecimal> MASS_CONC = Map.of(
            "g/dL", BigDecimal.ONE,
            "g/L", new BigDecimal("0.1"),
            "mg/dL", new BigDecimal("0.001")
    );

    /** 血糖专用：mg/dL -> mmol/L，除以 18.016（葡萄糖摩尔质量） */
    private static final BigDecimal MG_DL_PER_MMOL_L_GLUCOSE = new BigDecimal("18.016");

    public Optional<Converted> toCanonical(String loincCode, BigDecimal value, String sourceUnit) {
        String canonical = CANONICAL_UNIT.get(loincCode);
        if (canonical == null || sourceUnit == null || sourceUnit.isBlank()) {
            return Optional.empty();
        }
        String unit = sourceUnit.trim();

        // 血糖：mg/dL 与 mmol/L 之间的特殊换算
        if (loincCode.equals("2345-7")) {
            if (unit.equals("mg/dL")) {
                return Optional.of(round(value.divide(MG_DL_PER_MMOL_L_GLUCOSE,
                        MathContext.DECIMAL64), canonical));
            }
            return scale(MOLAR, value, unit, canonical);
        }
        // 体温：Cel <-> [degF] 是仿射换算，单独处理
        if (loincCode.equals("8310-5")) {
            if (unit.equals("Cel")) {
                return Optional.of(round(value, canonical));
            }
            if (unit.equals("[degF]")) {
                BigDecimal c = value.subtract(new BigDecimal("32"))
                        .multiply(new BigDecimal("5"))
                        .divide(new BigDecimal("9"), MathContext.DECIMAL64);
                return Optional.of(round(c, canonical));
            }
            return Optional.empty();
        }
        return switch (canonical) {
            case "mm[Hg]" -> scale(PRESSURE, value, unit, canonical);
            case "/min" -> scale(RATE, value, unit, canonical);
            case "g/dL" -> scale(MASS_CONC, value, unit, canonical);
            default -> Optional.empty();
        };
    }

    /** 同量纲线性换算：canonical = value * factor(unit) / factor(canonical) */
    private static Optional<Converted> scale(Map<String, BigDecimal> group, BigDecimal value,
                                             String unit, String canonical) {
        BigDecimal from = group.get(unit);
        BigDecimal to = group.get(canonical);
        if (from == null || to == null) {
            return Optional.empty();
        }
        return Optional.of(round(value.multiply(from).divide(to, MathContext.DECIMAL64), canonical));
    }

    private static Converted round(BigDecimal v, String canonical) {
        BigDecimal r = v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return new Converted(r, canonical);
    }
}
