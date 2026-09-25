package com.github.h3yow.clinicaldatanormalizerjava.export;

import java.util.List;

/**
 * 去标识化导出结果：记录不含直接标识符，digest 对同一数据版本与
 * 同一授权范围可复现。
 */
public record ExportResult(List<String> records, String digest, String dataVersion) {
}
