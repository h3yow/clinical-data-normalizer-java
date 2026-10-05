package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.ParseErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解析层测试：重复段/重复字段/转义/字符集还原，以及畸形消息的分类拒绝。
 */
class Hl7ParserTest {

    private static final Logger log = LoggerFactory.getLogger(Hl7ParserTest.class);

    private final Hl7Parser parser = new Hl7Parser();

    private static String oru(String obxValue, String unit, String obx3) {
        return "MSH|^~\\&|HIS_A|FAC|LAB_X|FAC|20260920100000+0800||ORU^R01|MSG001|P|2.5.1||||||UTF-8|||"
                + "\rPID|||P-0001||Doe^John\r"
                + "OBR|1||B-0001|" + obx3 + "\r"
                + "OBX|1|NM|" + obx3 + "||" + obxValue + "|" + unit + "|||N||F|||20260920100000+0800\r";
    }

    @Test
    void parsesBasicMessageWithCorrectFieldNumbering() {
        List<Hl7Message> messages = parser.parseBatch(oru("72", "bpm", "8867-4^Heart rate^LN"));
        assertEquals(1, messages.size());
        Hl7Message msg = messages.get(0);
        assertEquals("|", msg.segment("MSH").get(1), "MSH-1 必须是字段分隔符");
        assertEquals("^~\\&", msg.segment("MSH").get(2), "MSH-2 必须是编码字符");
        assertEquals("ORU", msg.segment("MSH").get(9, 0, 0));
        assertEquals("R01", msg.segment("MSH").get(9, 0, 1));
        assertEquals("MSG001", msg.segment("MSH").get(10));
        assertEquals("P-0001", msg.segment("PID").get(3, 0, 0));
        assertEquals("72", msg.segment("OBX").get(5));
        assertEquals("bpm", msg.segment("OBX").get(6));
        log.info("[解析成功] MSH-9={}^{}, OBX-5={}, OBX-6={} —— 判定: 字段编号符合 HL7 约定",
                msg.segment("MSH").get(9, 0, 0), msg.segment("MSH").get(9, 0, 1),
                msg.segment("OBX").get(5), msg.segment("OBX").get(6));
    }

    @Test
    void parsesRepeatedFieldsSegmentsAndEscapes() {
        String text = "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|MSG-RPT|P|2.5.1\r"
                + "PID|||P-0001~MR-9||Doe^A\\S\\B\\T\\C\r"
                + "OBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4||70~80|bpm||||F\r"
                + "OBX|2|NM|8867-4||90\\F\\2|bpm||||F\r";
        Hl7Message msg = parser.parseBatch(text).get(0);

        assertEquals(2, msg.segment("PID").field(3).repetitionCount(), "PID-3 重复");
        assertEquals("MR-9", msg.segment("PID").get(3, 1, 0));
        assertEquals(2, msg.segments("OBX").size(), "重复 OBX 段应全部保留");
        assertEquals(2, msg.segment("OBX").field(5).repetitionCount(), "OBX-5 重复字段");
        assertEquals("70", msg.segment("OBX").get(5, 0, 0));
        assertEquals("80", msg.segment("OBX").get(5, 1, 0));
        assertEquals("A^B&C", msg.segment("PID").get(5, 0, 1),
                "\\S\\ -> ^，\\T\\ -> & 还原");
        assertEquals("90|2", msg.segments("OBX").get(1).get(5), "\\F\\ -> | 还原");
        log.info("[重复与转义] PID-3 重复数={}, OBX 段数={}, OBX-5 重复=70/80, \\S\\=^,\\T\\=&,\\F\\=| —— 判定: 标准语义还原正确",
                msg.segment("PID").field(3).repetitionCount(), msg.segments("OBX").size());
    }

    @Test
    void parsesBatchWithBhsFrameAndMultipleMessages() {
        String text = "FHS|^~\\&|HIS_A\rBHS|^~\\&|HIS_A\r"
                + oru("70", "bpm", "8867-4").replace("MSG001", "MSG100")
                + oru("71", "bpm", "8867-4").replace("MSG001", "MSG101")
                + "BTS|2\rFTS|1\r";
        List<Hl7Message> messages = parser.parseBatch(text);
        assertEquals(2, messages.size());
        assertEquals("MSG100", messages.get(0).segment("MSH").get(10));
        assertEquals("MSG101", messages.get(1).segment("MSH").get(10));
        log.info("[批量帧] FHS/BHS 信封内解析出 {} 条消息 —— 判定: 批量信封解析正确", messages.size());
    }

    @Test
    void parsesHexEscapeUnderUtf8() {
        // \X2C\ = ','
        String text = "MSH|^~\\&|HIS_A|||FAC|20260920100000+0800||ORU^R01|MSG-X|P|2.5.1||||||UTF-8\r"
                + "PID|||P-1||Smith^John\\X2c\\Jr\rOBR|1||B|8867-4\rOBX|1|NM|8867-4||70|bpm||||F\r";
        Hl7Message msg = parser.parseBatch(text.getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals("John,Jr", msg.segment("PID").get(5, 0, 1));
        log.info("[十六进制转义] \\X2c\\ -> ',' —— 判定: 按 UTF-8 解码十六进制转义正确");
    }

    @Test
    void rejectsHighBytesWithoutCharset() {
        byte[] payload = ("MSH|^~\\&|HIS_A|||FAC|20260920100000+0800||ORU^R01|MSG-B|P|2.5.1\r"
                + "PID|||P-1||Müller\rOBR|1||B|8867-4\rOBX|1|NM|8867-4||70|bpm||||F\r")
                .replace("Müller", "Müller").getBytes(StandardCharsets.ISO_8859_1);
        Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parseBatch(payload));
        assertEquals(ParseErrorReason.MALFORMED_TEXT_ENCODING, ex.getReason());
        log.info("[字符集拒绝] 含非 ASCII 字节且未声明 MSH-18 -> {} —— 判定: 不以默认字符集兜底",
                ex.getReason());
    }

    @Test
    void acceptsMultibyteContentWhenCharsetDeclaredAtMsh18() {
        // MSH-18=UTF-8（标准字段位置 fields[17]），负载含 UTF-8 多字节字符：
        // 合法声明不得被误判为 MALFORMED_TEXT_ENCODING
        byte[] payload = ("MSH|^~\\&|HIS_A|||FAC|20260920100000+0800||ORU^R01|MSG-C|P|2.5.1||||||UTF-8\r"
                + "PID|||P-1||Müller\rOBR|1||B|8867-4\rOBX|1|NM|8867-4||70|bpm||||F\r")
                .getBytes(StandardCharsets.UTF_8);
        Hl7Message msg = parser.parseBatch(payload).get(0);
        assertEquals("Müller", msg.segment("PID").get(5, 0, 0));
        log.info("[字符集校准] MSH-18=UTF-8 合法声明 + 多字节姓名 -> 正常解析 —— 判定: 按标准字段位置读取 MSH-18");
    }

    @Test
    void usesFirstRepetitionOfMsh18() {
        // MSH-18 可重复：取第一重复（UTF-8~ASCII 以 UTF-8 解码）
        byte[] payload = ("MSH|^~\\&|HIS_A|||FAC|20260920100000+0800||ORU^R01|MSG-R|P|2.5.1||||||UTF-8~ASCII\r"
                + "PID|||P-1||Müller\rOBR|1||B|8867-4\rOBX|1|NM|8867-4||70|bpm||||F\r")
                .getBytes(StandardCharsets.UTF_8);
        Hl7Message msg = parser.parseBatch(payload).get(0);
        assertEquals("Müller", msg.segment("PID").get(5, 0, 0));
        log.info("[字符集校准] MSH-18=UTF-8~ASCII 取第一重复 -> 正常解析 —— 判定: 重复字段按标准取第一值");
    }

    @Test
    void rejectsUnsupportedCharset() {
        byte[] payload = ("MSH|^~\\&|HIS_A|||FAC|20260920100000+0800||ORU^R01|MSG-U|P|2.5.1||||||EDFIC\r"
                + "PID|||P-1\rOBR|1||B|8867-4\rOBX|1|NM|8867-4||70|bpm||||F\r")
                .getBytes(StandardCharsets.US_ASCII);
        Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parseBatch(payload));
        assertEquals(ParseErrorReason.UNSUPPORTED_CHARSET, ex.getReason());
        log.info("[字符集拒绝] MSH-18=EDFIC -> {} —— 判定: 不受支持字符集明确拒绝", ex.getReason());
    }

    @Test
    void rejectsInvalidEscape() {
        String text = "MSH|^~\\&|HIS_A|||FAC|20260920100000+0800||ORU^R01|MSG-E|P|2.5.1\r"
                + "PID|||P-1||Bad\\Q\\Name\rOBR|1||B|8867-4\rOBX|1|NM|8867-4||70|bpm||||F\r";
        Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parseBatch(text));
        assertEquals(ParseErrorReason.INVALID_ESCAPE_SEQUENCE, ex.getReason());
        log.info("[转义拒绝] 未知转义 \\Q\\ -> {} —— 判定: 不静默丢弃或猜测", ex.getReason());
    }

    @Test
    void rejectsNonMshStart() {
        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parseBatch("XXX|1\r"));
        assertEquals(ParseErrorReason.UNEXPECTED_FRAME_START, ex.getReason());
        log.info("[结构拒绝] 首段非 MSH/FHS/BHS -> {}", ex.getReason());
    }

    @Test
    void rejectsShortMsh() {
        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parseBatch("MSH|\r"));
        assertEquals(ParseErrorReason.MSH_TOO_SHORT, ex.getReason());
        log.info("[结构拒绝] MSH 缺少编码字符 -> {}", ex.getReason());
    }

    @Test
    void rejectsUnclosedBatchFrame() {
        String text = "FHS|^~\\&|HIS_A\r"
                + oru("70", "bpm", "8867-4").replace("MSG001", "MSG100");
        Hl7ParseException ex = assertThrows(Hl7ParseException.class, () -> parser.parseBatch(text));
        assertEquals(ParseErrorReason.UNCLOSED_BATCH_FRAME, ex.getReason());
        log.info("[结构拒绝] FHS 无 FTS -> {}", ex.getReason());
    }

    @Test
    void rejectsEmptyPayload() {
        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parseBatch(new byte[0]));
        assertEquals(ParseErrorReason.EMPTY_PAYLOAD, ex.getReason());
        log.info("[结构拒绝] 空负载 -> {}", ex.getReason());
    }
}
