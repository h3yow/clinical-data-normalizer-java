package com.github.h3yow.clinicaldatanormalizerjava.web;

import com.github.h3yow.clinicaldatanormalizerjava.export.ExportBundle;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportService;
import com.github.h3yow.clinicaldatanormalizerjava.export.VersionInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 去标识化导出入口（当前版本 + 历史版本复盘 + 版本历史查询）。
 *
 * <pre>
 * GET /api/export?grantId=grant-all-demo&amp;patientId=P-0001&amp;categories=vital_signs,laboratory
 * GET /api/export?grantId=...&amp;patientId=...&amp;categories=...&amp;version=3   （按历史版本复盘）
 * GET /api/export/versions?grantId=...&amp;patientId=...                          （版本历史）
 * </pre>
 * 授权失败由 {@link ApiExceptionHandler} 统一转换为 403 + 可区分错误原因；
 * 指定版本不存在时转换为 404 + VERSION_NOT_FOUND。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ExportBundle export(@RequestParam String grantId,
                               @RequestParam String patientId,
                               @RequestParam(required = false) Set<String> categories,
                               @RequestParam(required = false) Long version) {
        return exportService.export(grantId, patientId, categories, version);
    }

    @GetMapping("/versions")
    public List<VersionInfo> versions(@RequestParam String grantId,
                                      @RequestParam String patientId) {
        return exportService.listVersions(grantId, patientId);
    }
}
