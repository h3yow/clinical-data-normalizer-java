package com.github.h3yow.clinicaldatanormalizerjava.web;

import com.github.h3yow.clinicaldatanormalizerjava.export.ExportBundle;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 去标识化导出入口。
 *
 * <pre>
 * GET /api/export?grantId=grant-all-demo&amp;patientId=P-0001&amp;categories=vital_signs,laboratory
 * </pre>
 * 授权失败由 {@link ApiExceptionHandler} 统一转换为 403 + 可区分错误原因。
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
                               @RequestParam(required = false) Set<String> categories) {
        return exportService.export(grantId, patientId, categories);
    }
}
