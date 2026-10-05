package com.github.h3yow.clinicaldatanormalizerjava.hl7;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 严格的 HL7 v2 解析器。
 *
 * <p>支持的标准语义：
 * <ul>
 *   <li>批量帧：FHS..FTS / BHS..BTS 信封，信封内可含多条 MSH 消息；无信封时直接以 MSH 起始。</li>
 *   <li>段结束符 CR（同时容忍 LF/CRLF），重复段按出现顺序保留。</li>
 *   <li>字段分隔符 |、重复分隔符 ~、组件分隔符 ^、子组件分隔符 &amp;（取自 MSH-1/MSH-2）。</li>
 *   <li>转义还原：\F\（|）、\S\（^）、\T\（&amp;）、\R\（~）、\E\（\）、\.br\、
 *       \Xhhhh\（按 MSH-18 声明字符集）、本地转义 \Z..\。</li>
 *   <li>字符集：由 MSH-18 声明；缺省按 US-ASCII 严格解码，非法字节直接拒绝。</li>
 * </ul>
 * 任何结构不完整、编码非法、转义无法识别的输入都抛出带 {@link ParseErrorReason} 的异常，
 * 不以默认值或静默截断兜底。
 */
public class Hl7Parser {

    private static final Pattern SEGMENT_NAME = Pattern.compile("[A-Z][A-Z0-9]{2}");
    private static final Map<String, Charset> CHARSET_BY_HL7_NAME = Map.of(
            "ASCII", StandardCharsets.US_ASCII,
            "8859/1", StandardCharsets.ISO_8859_1,
            "ISO 8859-1", StandardCharsets.ISO_8859_1,
            "UTF-8", StandardCharsets.UTF_8,
            "UNICODE UTF-8", StandardCharsets.UTF_8,
            "GB18030", Charset.forName("GB18030"),
            "GBK", Charset.forName("GBK")
    );

    /** 解析字节负载（按 MSH-18 字符集解码），返回其中的全部消息。 */
    public List<Hl7Message> parseBatch(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new Hl7ParseException(ParseErrorReason.EMPTY_PAYLOAD, "payload 为空");
        }
        // 先用单字节无损编码定位 MSH-18，确定真实字符集后再严格解码整包。
        String latin1 = new String(payload, StandardCharsets.ISO_8859_1);
        Charset charset = determineCharset(latin1);
        String text = decodeStrict(payload, charset);
        return parseText(text, charset);
    }

    /** 测试/便捷入口：字符集按 UTF-8 处理（仍走同样的结构校验）。 */
    public List<Hl7Message> parseBatch(String text) {
        if (text == null || text.isBlank()) {
            throw new Hl7ParseException(ParseErrorReason.EMPTY_PAYLOAD, "payload 为空");
        }
        return parseText(text, StandardCharsets.UTF_8);
    }

    private Charset determineCharset(String latin1) {
        List<String> lines = splitLines(latin1);
        String first = firstNonBlank(lines);
        if (first == null) {
            throw new Hl7ParseException(ParseErrorReason.EMPTY_PAYLOAD, "payload 为空");
        }
        String mshLine = firstMshLine(lines, first);
        if (mshLine == null) {
            // 没有 MSH 就无法读取 MSH-18；结构问题稍后在帧校验中报出。
            return StandardCharsets.US_ASCII;
        }
        if (mshLine.length() < 8) {
            // 至少要能拿到 MSH + 字段分隔符 + 编码字符
            return StandardCharsets.US_ASCII;
        }
        // MSH-1 即字段分隔符本身（标准允许非 '|'，此处按实际字符切分）
        char fieldSep = mshLine.charAt(3);
        String[] fields = mshLine.split(Pattern.quote(String.valueOf(fieldSep)), -1);
        // MSH 字段编号：fields[0]="MSH"，fields[1]=MSH-2（编码字符），fields[n-1]=MSH-n，
        // 因此 MSH-18（字符集）位于 fields[17]
        if (fields.length <= 17 || fields[17].isBlank()) {
            return StandardCharsets.US_ASCII;
        }
        // MSH-18 可重复（如 "ASCII~8859/1"），以第一重复的第一组件为准；
        // 重复/组件分隔符取自 MSH-2 声明
        String encChars = fields[1];
        char repSep = encChars.length() >= 2 ? encChars.charAt(1) : '~';
        char compSep = encChars.isEmpty() ? '^' : encChars.charAt(0);
        String firstRepetition = fields[17].split(Pattern.quote(String.valueOf(repSep)), 2)[0];
        String name = firstRepetition.split(Pattern.quote(String.valueOf(compSep)), 2)[0].trim();
        Charset cs = CHARSET_BY_HL7_NAME.get(name.toUpperCase());
        if (cs == null) {
            throw new Hl7ParseException(ParseErrorReason.UNSUPPORTED_CHARSET, "不支持的 MSH-18 字符集: " + name);
        }
        return cs;
    }

    private String firstMshLine(List<String> lines, String first) {
        if (first.startsWith("MSH")) {
            return first;
        }
        if (first.startsWith("FHS") || first.startsWith("BHS")) {
            for (String line : lines) {
                if (line.startsWith("MSH")) {
                    return line;
                }
            }
        }
        return null;
    }

    private String decodeStrict(byte[] payload, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(payload)).toString();
        } catch (Exception e) {
            throw new Hl7ParseException(ParseErrorReason.MALFORMED_TEXT_ENCODING,
                    "按字符集 " + charset.name() + " 解码失败: " + e.getMessage());
        }
    }

    private List<Hl7Message> parseText(String text, Charset charset) {
        List<String> lines = splitLines(text);
        String first = firstNonBlank(lines);
        if (first == null) {
            throw new Hl7ParseException(ParseErrorReason.EMPTY_PAYLOAD, "payload 为空");
        }
        boolean framed = first.startsWith("FHS") || first.startsWith("BHS");
        if (!framed && !first.startsWith("MSH")) {
            throw new Hl7ParseException(ParseErrorReason.UNEXPECTED_FRAME_START,
                    "批量数据必须以 FHS/BHS/MSH 起始，实际: " + safePrefix(first));
        }

        List<Hl7Message> messages = new ArrayList<>();
        List<String> current = null;
        int fileDepth = 0;   // FHS/FTS 配对
        int batchDepth = 0;  // BHS/BTS 配对
        boolean sawFrame = false;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String tag = line.length() >= 3 ? line.substring(0, 3) : line;
            switch (tag) {
                case "FHS" -> {
                    sawFrame = true;
                    fileDepth++;
                }
                case "BHS" -> {
                    sawFrame = true;
                    batchDepth++;
                }
                case "FTS" -> {
                    if (current != null) {
                        messages.add(buildMessage(current, charset));
                        current = null;
                    }
                    fileDepth--;
                    if (fileDepth < 0) {
                        throw new Hl7ParseException(ParseErrorReason.UNCLOSED_BATCH_FRAME, "出现无 FHS 配对的 FTS");
                    }
                }
                case "BTS" -> {
                    if (current != null) {
                        messages.add(buildMessage(current, charset));
                        current = null;
                    }
                    batchDepth--;
                    if (batchDepth < 0) {
                        throw new Hl7ParseException(ParseErrorReason.UNCLOSED_BATCH_FRAME, "出现无 BHS 配对的 BTS");
                    }
                }
                default -> {
                    if (tag.equals("MSH")) {
                        if (!sawFrame && !framed) {
                            // 无信封批处理：每个 MSH 开启一条消息
                        }
                        if (current != null) {
                            messages.add(buildMessage(current, charset));
                        }
                        current = new ArrayList<>();
                        current.add(line);
                    } else {
                        if (current == null) {
                            if (!framed && !sawFrame) {
                                throw new Hl7ParseException(ParseErrorReason.UNEXPECTED_FRAME_START,
                                        "消息必须以 MSH 起始，实际段: " + tag);
                            }
                            // 信封尾段（如 BTS）以外、消息之外的普通段
                            throw new Hl7ParseException(ParseErrorReason.UNEXPECTED_FRAME_START,
                                    "MSH 之外出现数据段: " + tag);
                        }
                        current.add(line);
                    }
                }
            }
        }
        if (current != null) {
            if (framed || sawFrame) {
                throw new Hl7ParseException(ParseErrorReason.UNCLOSED_BATCH_FRAME,
                        "批量信封未以 FTS/BTS 结束，仍有未闭合的 MSH 消息");
            }
            messages.add(buildMessage(current, charset));
        }
        if (fileDepth != 0 || batchDepth != 0) {
            throw new Hl7ParseException(ParseErrorReason.UNCLOSED_BATCH_FRAME, "FHS/FTS 或 BHS/BTS 未配对");
        }
        if (messages.isEmpty()) {
            throw new Hl7ParseException(ParseErrorReason.UNEXPECTED_FRAME_START, "批量数据中没有任何 MSH 消息");
        }
        return messages;
    }

    private Hl7Message buildMessage(List<String> lines, Charset charset) {
        List<Hl7Message.Segment> segments = new ArrayList<>();
        char fieldSep = 0;
        char compSep = 0;
        char repSep = 0;
        char subSep = 0;
        char escSep = 0;

        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            String tag = line.substring(0, 3);
            if (!SEGMENT_NAME.matcher(tag).matches()) {
                throw new Hl7ParseException(ParseErrorReason.INVALID_SEGMENT_NAME,
                        "非法段名: " + tag);
            }
            boolean isMsh = li == 0 && tag.equals("MSH");
            String encChars = null;
            if (isMsh) {
                if (line.length() < 4) {
                    throw new Hl7ParseException(ParseErrorReason.MSH_TOO_SHORT, "MSH 段缺少字段分隔符");
                }
                fieldSep = line.charAt(3);
                if (line.length() < 5) {
                    throw new Hl7ParseException(ParseErrorReason.MSH_TOO_SHORT, "MSH 段缺少编码字符");
                }
                String enc = line.substring(4);
                encChars = enc.indexOf(fieldSep) >= 0 ? enc.substring(0, enc.indexOf(fieldSep)) : enc;
                if (encChars.length() < 2 || encChars.length() > 4) {
                    throw new Hl7ParseException(ParseErrorReason.INVALID_ENCODING_CHARACTERS,
                            "MSH-2 编码字符应为 2~4 个，实际: '" + encChars + "'");
                }
                if (hasDuplicateChar(encChars)) {
                    throw new Hl7ParseException(ParseErrorReason.INVALID_ENCODING_CHARACTERS,
                            "MSH-2 编码字符存在重复: '" + encChars + "'");
                }
                compSep = encChars.charAt(0);
                repSep = encChars.charAt(1);
                escSep = encChars.charAt(2);
                subSep = encChars.length() == 4 ? encChars.charAt(3) : 0;
            } else if (li == 0) {
                throw new Hl7ParseException(ParseErrorReason.UNEXPECTED_FRAME_START,
                        "消息首段必须为 MSH，实际: " + tag);
            }

            List<Hl7Message.Field> fields = new ArrayList<>();
            // 所有段统一：fields 下标即 HL7 字段编号（fields.get(3) 即 PID-3/MSH-3）
            fields.add(null);
            if (isMsh) {
                fields.add(new Hl7Message.Field(List.of(new Hl7Message.Repetition(
                        List.of(new Hl7Message.Component(List.of(String.valueOf(fieldSep))))))));
                String msh2 = encChars;
                // MSH-2 本身按字面文本返回，不做转义
                fields.add(new Hl7Message.Field(List.of(new Hl7Message.Repetition(
                        List.of(new Hl7Message.Component(List.of(msh2)))))));
                int secondSep = line.indexOf(fieldSep, 4);
                String rest = secondSep >= 0 ? line.substring(secondSep + 1) : "";
                // rest 的首元素是 MSH-3；splitFields 从 out 当前尾部（index 2 之后）继续追加，
                // 因此结果下标恰好为 3、4、…，与 MSH 字段编号一致
                splitFields(rest, fieldSep, compSep, repSep, subSep, escSep, charset, fields);
            } else {
                int sep = line.indexOf(fieldSep);
                if (sep < 0) {
                    throw new Hl7ParseException(ParseErrorReason.INCOMPLETE_SEGMENT,
                            "段 " + tag + " 缺少字段分隔符，段体不完整");
                }
                String body = line.substring(sep + 1);
                splitFields(body, fieldSep, compSep, repSep, subSep, escSep, charset, fields);
            }
            segments.add(new Hl7Message.Segment(tag, fields));
        }
        return new Hl7Message(segments);
    }

    private void splitFields(String body, char fieldSep, char compSep, char repSep, char subSep,
                             char escSep, Charset charset, List<Hl7Message.Field> out) {
        List<String> rawFields = splitOn(body, fieldSep);
        // splitOn 保留前导空串（body 以分隔符开头时），首元素即字段 1
        for (String raw : rawFields) {
            List<Hl7Message.Repetition> reps = new ArrayList<>();
            for (String rawRep : splitOn(raw, repSep)) {
                List<Hl7Message.Component> comps = new ArrayList<>();
                for (String rawComp : splitOn(rawRep, compSep)) {
                    List<String> subs = new ArrayList<>();
                    if (subSep != 0) {
                        for (String rawSub : splitOn(rawComp, subSep)) {
                            subs.add(unescape(rawSub, fieldSep, compSep, repSep, subSep, escSep, charset));
                        }
                    } else {
                        subs.add(unescape(rawComp, fieldSep, compSep, repSep, subSep, escSep, charset));
                    }
                    comps.add(new Hl7Message.Component(subs));
                }
                reps.add(new Hl7Message.Repetition(comps));
            }
            out.add(new Hl7Message.Field(reps));
        }
    }

    private List<String> splitOn(String s, char sep) {
        if (sep == 0) {
            return List.of(s);
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            // 在每个分隔符处切一刀；i == s.length() 时补上最后一段（可能为空串）。
            // 因此 "a||" -> ["a","",""]，前导与尾随空字段都保留，字段编号不会错位。
            if (i == s.length() || s.charAt(i) == sep) {
                result.add(s.substring(start, i));
                start = i + 1;
            }
        }
        return result;
    }

    /**
     * 还原 HL7 转义序列。escSep 在标准实现中为 '\'。
     * 不认识的转义、缺少结束符、非法十六进制一律拒绝。
     */
    private String unescape(String s, char fieldSep, char compSep, char repSep, char subSep,
                            char escSep, Charset charset) {
        if (escSep == 0 || s.indexOf(escSep) < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != escSep) {
                sb.append(c);
                i++;
                continue;
            }
            int end = s.indexOf(escSep, i + 1);
            if (end < 0) {
                throw new Hl7ParseException(ParseErrorReason.INVALID_ESCAPE_SEQUENCE,
                        "转义序列缺少结束符: " + safeSuffix(s, i));
            }
            String code = s.substring(i + 1, end);
            if (code.isEmpty()) {
                throw new Hl7ParseException(ParseErrorReason.INVALID_ESCAPE_SEQUENCE, "空转义序列");
            }
            if (code.length() == 1) {
                switch (code.charAt(0)) {
                    case 'F', 'f' -> sb.append(fieldSep);
                    case 'S', 's' -> sb.append(compSep);
                    case 'T', 't' -> sb.append(subSep != 0 ? subSep : '&');
                    case 'E', 'e' -> sb.append(escSep);
                    case 'R', 'r' -> sb.append(repSep);
                    default -> throw new Hl7ParseException(ParseErrorReason.INVALID_ESCAPE_SEQUENCE,
                            "不支持的转义序列: " + escSep + code + escSep);
                }
            } else if (code.equals(".br") || code.equals(".BR")) {
                sb.append('\n');
            } else if ((code.charAt(0) == 'X' || code.charAt(0) == 'x')) {
                String hex = code.substring(1);
                if (hex.isEmpty() || hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]+")) {
                    throw new Hl7ParseException(ParseErrorReason.INVALID_ESCAPE_SEQUENCE,
                            "非法十六进制转义: " + escSep + code + escSep);
                }
                byte[] bytes = new byte[hex.length() / 2];
                for (int k = 0; k < bytes.length; k++) {
                    bytes[k] = (byte) Integer.parseInt(hex.substring(k * 2, k * 2 + 2), 16);
                }
                sb.append(decodeStrict(bytes, charset));
            } else if (code.charAt(0) == 'Z' || code.charAt(0) == 'z') {
                // 本地转义：按标准约定原样保留其中的文本
                sb.append(code, 1, code.length());
            } else {
                throw new Hl7ParseException(ParseErrorReason.INVALID_ESCAPE_SEQUENCE,
                        "不支持的转义序列: " + escSep + code + escSep);
            }
            i = end + 1;
        }
        return sb.toString();
    }

    private boolean hasDuplicateChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            for (int j = i + 1; j < s.length(); j++) {
                if (s.charAt(i) == s.charAt(j)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                lines.add(cur.toString());
                cur.setLength(0);
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                lines.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        lines.add(cur.toString());
        return lines;
    }

    private String firstNonBlank(List<String> lines) {
        for (String l : lines) {
            if (!l.isBlank()) {
                return l;
            }
        }
        return null;
    }

    private String safePrefix(String s) {
        return s.length() <= 20 ? s : s.substring(0, 20);
    }

    private String safeSuffix(String s, int from) {
        String tail = s.substring(Math.min(from, s.length()));
        return tail.length() <= 20 ? tail : tail.substring(0, 20);
    }
}
