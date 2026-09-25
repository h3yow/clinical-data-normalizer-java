package com.github.h3yow.clinicaldatanormalizerjava.hl7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条 HL7 v2 消息的解析结果：段 → 字段 → 重复 → 组件 → 子组件。
 * <p>编号约定与 HL7 标准完全一致：{@code field(n)} 即第 n 号字段
 * （MSH-1 为字段分隔符 "|"，MSH-2 为编码字符 "^~\&"，field(3) 即 MSH-3；
 * 其它段同理，如 PID 段 field(3) 即 PID-3）。fields 列表第 0 位为占位 null。
 */
public final class Hl7Message {

    private final List<Segment> segments;

    public Hl7Message(List<Segment> segments) {
        this.segments = List.copyOf(segments);
    }

    public List<Segment> segments() {
        return segments;
    }

    /** 返回指定段名的第一个段，不存在返回 null。 */
    public Segment segment(String name) {
        for (Segment s : segments) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 返回指定段名的全部段（保留重复段与出现顺序）。 */
    public List<Segment> segments(String name) {
        List<Segment> result = new ArrayList<>();
        for (Segment s : segments) {
            if (s.name().equals(name)) {
                result.add(s);
            }
        }
        return result;
    }

    /** 段：name 为段名，fields 按下标对应 HL7 字段编号（fields.get(0) 为占位 null）。 */
    public record Segment(String name, List<Field> fields) {

        public Segment {
            fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }

        public int fieldCount() {
            return fields.size();
        }

        public boolean hasField(int n) {
            return n >= 0 && n < fields.size();
        }

        /** 取第 n 个字段；不存在时返回 null（由归一化层判定是否必填缺失）。 */
        public Field field(int n) {
            return (n >= 0 && n < fields.size()) ? fields.get(n) : null;
        }

        /** 第一重复、第一组件、第一子组件的文本；字段不存在返回 null。 */
        public String get(int n) {
            return get(n, 0, 0, 0);
        }

        public String get(int n, int rep, int comp) {
            return get(n, rep, comp, 0);
        }

        public String get(int n, int rep, int comp, int sub) {
            Field f = field(n);
            if (f == null || rep >= f.repetitions().size()) {
                return null;
            }            List<Component> comps = f.repetitions().get(rep).components();
            if (comp >= comps.size()) {
                return null;
            }
            List<String> subs = comps.get(comp).subcomponents();
            return sub < subs.size() ? subs.get(sub) : null;
        }
    }

    /** 字段：含 1 个以上重复值。 */
    public record Field(List<Repetition> repetitions) {
        public Field {
            repetitions = List.copyOf(repetitions);
        }

        public int repetitionCount() {
            return repetitions.size();
        }
    }

    /** 重复：含 1 个以上组件。 */
    public record Repetition(List<Component> components) {
        public Repetition {
            components = List.copyOf(components);
        }
    }

    /** 组件：含 1 个以上子组件（无子组件分隔符时只有 1 个元素）。 */
    public record Component(List<String> subcomponents) {
        public Component {
            subcomponents = List.copyOf(subcomponents);
        }

        /** 第一子组件文本。 */
        public String value() {
            return subcomponents.get(0);
        }
    }
}
