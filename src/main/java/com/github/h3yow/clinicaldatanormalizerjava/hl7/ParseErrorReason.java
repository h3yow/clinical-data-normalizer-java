package com.github.h3yow.clinicaldatanormalizerjava.hl7;

/**
 * 解析阶段（HL7 v2 词法/结构层）的错误原因。
 * 每一种原因都可被调用方区分，不使用默认值兜底。
 */
public enum ParseErrorReason {
    /** 输入为空或只有空白 */
    EMPTY_PAYLOAD,
    /** 消息首段不是 MSH（批量信封下不是 FHS/BHS/MSH） */
    UNEXPECTED_FRAME_START,
    /** MSH 段长度不足，无法取出分隔符（MSH-1/MSH-2） */
    MSH_TOO_SHORT,
    /** MSH-2 编码字符个数非法（应为 2~4 个，且各字符互不相同） */
    INVALID_ENCODING_CHARACTERS,
    /** MSH 声明的字符集（MSH-18）不受支持 */
    UNSUPPORTED_CHARSET,
    /** 按声明字符集解码字节序列失败 */
    MALFORMED_TEXT_ENCODING,
    /** 出现无法识别或不完整的转义序列（如 \X 或 \XZZ\） */
    INVALID_ESCAPE_SEQUENCE,
    /** 批量信封 FHS 没有以 BTS 收尾，或 BHS 没有以 BTS 收尾 */
    UNCLOSED_BATCH_FRAME,
    /** 段名不是 3 个大写字母/数字 */
    INVALID_SEGMENT_NAME,
    /** 段内字段索引缺失/越界（结构性不完整，例如空段体） */
    INCOMPLETE_SEGMENT
}
