package com.github.h3yow.clinicaldatanormalizerjava.hl7;

import java.util.List;

/**
 * 一个字段：重复值列表，每个重复值是组件列表，每个组件是子组件列表。
 * 文本均已做过转义还原。
 */
public record Hl7Field(List<List<List<String>>> repetitions) {

    public static Hl7Field empty() {
        return new Hl7Field(List.of());
    }

    public boolean isEmpty() {
        return repetitions.isEmpty() || repetitions.get(0).isEmpty()
                || repetitions.get(0).get(0).isEmpty()
                || repetitions.get(0).get(0).get(0).isEmpty();
    }

    public String firstText() {
        if (isEmpty()) {
            return "";
        }
        return repetitions.get(0).get(0).get(0);
    }

    /** 第一个重复值的第 componentIndex(1-based) 个组件文本 */
    public String component(int componentIndex) {
        if (repetitions.isEmpty()) {
            return "";
        }
        List<List<String>> components = repetitions.get(0);
        if (componentIndex < 1 || componentIndex > components.size()) {
            return "";
        }
        List<String> subs = components.get(componentIndex - 1);
        return subs.isEmpty() ? "" : subs.get(0);
    }

    public int repetitionCount() {
        return repetitions.size();
    }
}
