package com.github.h3yow.clinicaldatanormalizerjava.hl7;

import java.util.List;

/**
 * 一个 HL7 段：字段列表已按 | 切分，每个字段保留重复值(~)、
 * 组件(^)与子组件(&)结构，转义序列已还原。
 */
public record Hl7Segment(String name, List<Hl7Field> fields) {

    /** 取 1-based 字段（HL7 惯例，MSH 除外由解析器特殊处理），缺失返回空字段 */
    public Hl7Field field(int index) {
        if (index < 1 || index > fields.size()) {
            return Hl7Field.empty();
        }
        return fields.get(index - 1);
    }

    /** 第一个重复值的第一个组件的文本，缺失返回 "" */
    public String text(int index) {
        return field(index).firstText();
    }
}
