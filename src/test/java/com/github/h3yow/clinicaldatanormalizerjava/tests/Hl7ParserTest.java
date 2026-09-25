package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.RejectionReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class Hl7ParserTest {

    private static final Logger log = LoggerFactory.getLogger(Hl7ParserTest.class);
    private final Hl7Parser parser = new Hl7Parser();

    @Test
    void parsesEncodingCharsRepetitionsAndEscapes() {
        String raw = "MSH|^~\\&|LAB|L|HIS|H|20240101120000||ORU^R01|MSG1|P|2.5\r"
                + "PID|1||P123^^^HIS^MR||Doe^John\r"
                + "OBX|1|NM|GLU^Glucose^L|1|5.5|mmol/L|||||F|||20240101113000\r"
                + "OBX|2|NM|A^B^L~C^D^L|1|1\\F\\2|mg/dL|||||F|||20240101113000";
        Hl7Message msg = parser.parse(raw);
        log.info("parsed segments={}, charset={}, encChars={}",
                msg.segments().size(), msg.charset(), msg.encodingCharacters());

        assertEquals("|", msg.fieldSeparator());
        assertEquals("^~\\&", msg.encodingCharacters());
        assertEquals("LAB", msg.first("MSH").text(3));
        assertEquals("MSG1", msg.first("MSH").text(10));
        // 重复字段：OBX-3 有两个重复值
        var obx2 = msg.segments("OBX").get(1);
        assertEquals(2, obx2.field(3).repetitionCount());
        // 转义还原：\F\ -> |
        assertEquals("1|2", obx2.text(5));
        log.info("escape restored OBX-5='{}', repetitions of OBX-3={}",
                obx2.text(5), obx2.field(3).repetitionCount());
    }

    @Test
    void acceptsUtf8CharsetDeclaration() {
        String raw = "MSH|^~\\&|LAB|L|HIS|H|20240101120000||ORU^R01|MSG2|P|2.5||||||UNICODE UTF-8\r"
                + "PID|1||P1^^^HIS^MR";
        Hl7Message msg = parser.parse(raw);
        assertEquals("UNICODE UTF-8", msg.charset());
        log.info("charset accepted: {}", msg.charset());
    }

    @Test
    void rejectsMissingMsh() {
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse("PID|1||P1"));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.MALFORMED_STRUCTURE, e.getReason());
    }

    @Test
    void rejectsBadEncodingCharacters() {
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse("MSH|^~|LAB\rPID|1"));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.MALFORMED_STRUCTURE, e.getReason());
    }

    @Test
    void rejectsUnknownEscapeSequence() {
        String raw = "MSH|^~\\&|LAB|L|HIS|H|20240101120000||ORU^R01|MSG3|P|2.5\r"
                + "PID|1||P1\\Q\\^^^HIS^MR";
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse(raw));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.MALFORMED_STRUCTURE, e.getReason());
    }

    @Test
    void rejectsUnsupportedCharset() {
        String raw = "MSH|^~\\&|LAB|L|HIS|H|20240101120000||ORU^R01|MSG4|P|2.5||||||EBCDIC\r"
                + "PID|1||P1^^^HIS^MR";
        Hl7ParseException e = assertThrows(Hl7ParseException.class,
                () -> parser.parse(raw));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.UNSUPPORTED_MESSAGE, e.getReason());
    }
}
