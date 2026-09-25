package com.github.h3yow.clinicaldatanormalizerjava.hl7;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HL7 v2 解析器。
 *
 * 语义要点：
 * - MSH 段前两个字符位置定义字段分隔符与编码字符（组件/重复/转义/子组件）；
 * - 字段按分隔符切分，字段内按 ~ ^ & 切出重复值/组件/子组件；
 * - 转义序列 \F\ \S\ \R\ \E\ \T\ 与 \Xhh...\ 十六进制按标准还原，
 *   未知转义按结构错误拒绝，不做宽松处理；
 * - MSH-18 声明字符集，仅接受 ASCII / UTF-8 系列，其他按不支持拒绝；
 * - 段内字段数不足不报错（HL7 允许尾部省略），但缺必需段由上层校验。
 */
@Component
public class Hl7Parser {

    public Hl7Message parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE, "empty message");
        }
        // 段分隔：\r\n、\r、\n 均接受
        String[] lines = raw.strip().split("\r\n|\r|\n");
        List<String> segLines = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                segLines.add(line);
            }
        }
        if (segLines.isEmpty() || !segLines.get(0).startsWith("MSH")) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                    "message must start with MSH segment");
        }

        String msh = segLines.get(0);
        if (msh.length() < 4) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                    "MSH missing field separator");
        }
        String fieldSep = msh.substring(3, 4);
        // MSH-1 是分隔符本身，MSH-2 是编码字符，因此 MSH 的字段从原始行按分隔符切，
        // 切出的第 0 片是 "MSH"+分隔符，需要特殊处理。
        List<String> mshRawFields = splitPreserveTrailing(msh, fieldSep.charAt(0));
        if (mshRawFields.size() < 2 || mshRawFields.get(1).length() < 4) {
            throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                    "MSH-2 encoding characters must declare component/repetition/escape/subcomponent");
        }
        String encChars = mshRawFields.get(1).substring(0, 4);
        char comp = encChars.charAt(0);
        char rep = encChars.charAt(1);
        char esc = encChars.charAt(2);
        char sub = encChars.charAt(3);

        List<Hl7Segment> segments = new ArrayList<>();
        // MSH：fields[0] = MSH-1(分隔符), fields[1] = MSH-2(编码字符), 其后正常
        List<Hl7Field> mshFields = new ArrayList<>();
        mshFields.add(new Hl7Field(List.of(List.of(List.of(fieldSep)))));
        // MSH-2 是编码字符声明本身，必须按字面量保存，不能切分或转义还原
        mshFields.add(new Hl7Field(List.of(List.of(List.of(encChars)))));
        for (int i = 2; i < mshRawFields.size(); i++) {
            mshFields.add(parseField(mshRawFields.get(i), fieldSep.charAt(0), comp, rep, esc, sub));
        }
        segments.add(new Hl7Segment("MSH", mshFields));

        for (int i = 1; i < segLines.size(); i++) {
            String line = segLines.get(i);
            if (line.length() < 4 || line.charAt(3) != fieldSep.charAt(0)) {
                throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                        "segment " + i + " does not start with 3-char name + field separator: " + abbrev(line));
            }
            String name = line.substring(0, 3);
            List<String> rawFields = splitPreserveTrailing(line.substring(4), fieldSep.charAt(0));
            List<Hl7Field> fields = new ArrayList<>();
            for (String f : rawFields) {
                fields.add(parseField(f, fieldSep.charAt(0), comp, rep, esc, sub));
            }
            segments.add(new Hl7Segment(name, fields));
        }

        String charset = segments.get(0).text(18);
        if (!charset.isEmpty() && !isSupportedCharset(charset)) {
            throw new Hl7ParseException(RejectionReason.UNSUPPORTED_MESSAGE,
                    "unsupported character set in MSH-18: " + charset);
        }
        return new Hl7Message(segments, fieldSep, encChars,
                charset.isEmpty() ? "ASCII" : charset);
    }

    private static boolean isSupportedCharset(String cs) {
        String n = cs.trim().toUpperCase().replace("-", "").replace("_", "").replace(" ", "");
        return n.equals("ASCII") || n.equals("UTF8") || n.equals("88591")
                || n.equals("UNICODEUTF8") || n.equals("ISOIR100");
    }

    private static List<String> splitPreserveTrailing(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 切分字段为 重复值->组件->子组件，并对最内层文本做转义还原 */
    private static Hl7Field parseField(String raw, char fieldSep, char comp, char rep,
                                       char esc, char sub) {
        List<List<List<String>>> repetitions = new ArrayList<>();
        for (String repetition : splitPreserveTrailing(raw, rep)) {
            List<List<String>> components = new ArrayList<>();
            for (String component : splitPreserveTrailing(repetition, comp)) {
                List<String> subs = new ArrayList<>();
                for (String s : splitPreserveTrailing(component, sub)) {
                    subs.add(unescape(s, esc, fieldSep, comp, rep, sub));
                }
                components.add(subs);
            }
            repetitions.add(components);
        }
        return new Hl7Field(repetitions);
    }

    /**
     * 转义还原：\F\ \S\ \R\ \E\ \T\ 及 \Xhh...\（十六进制字节，按 UTF-8 解码）。
     * 未闭合或未知转义按结构错误拒绝。
     */
    static String unescape(String s, char esc, char fieldSep, char comp, char rep, char sub) {
        int idx = s.indexOf(esc);
        if (idx < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != esc) {
                out.append(c);
                i++;
                continue;
            }
            int end = s.indexOf(esc, i + 1);
            if (end < 0) {
                throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                        "unterminated escape sequence in: " + s);
            }
            String seq = s.substring(i + 1, end);
            switch (seq) {
                case "F" -> out.append(fieldSep);
                case "S" -> out.append(comp);
                case "R" -> out.append(rep);
                case "E" -> out.append(esc);
                case "T" -> out.append(sub);
                default -> {
                    if (seq.startsWith("X") && seq.length() > 1 && seq.length() % 2 == 1) {
                        out.append(decodeHex(seq.substring(1)));
                    } else if (seq.startsWith("H") || seq.startsWith("N")) {
                        // 高亮/正常显示标记，语义上可安全忽略
                    } else {
                        throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                                "unknown escape sequence: \\" + seq + "\\");
                    }
                }
            }
            i = end + 1;
        }
        return out.toString();
    }

    private static String decodeHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            try {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                throw new Hl7ParseException(RejectionReason.MALFORMED_STRUCTURE,
                        "invalid hex escape: " + hex);
            }
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String abbrev(String line) {
        return line.length() <= 20 ? line : line.substring(0, 20) + "...";
    }
}
