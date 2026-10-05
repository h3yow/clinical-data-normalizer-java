package com.github.h3yow.clinicaldatanormalizerjava.web;

import com.github.h3yow.clinicaldatanormalizerjava.export.ExportBundle;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportService;
import com.github.h3yow.clinicaldatanormalizerjava.export.VersionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 去标识化导出入口。
 *
 * <pre>
 * GET /api/export?grantId=grant-all-demo&amp;patientId=P-0001&amp;categories=vital_signs,laboratory
 * GET /api/export?grantId=grant-all-demo&amp;patientId=P-0001&amp;categories=vital_signs&amp;version=2
 * GET /api/export/versions?grantId=grant-all-demo&amp;patientId=P-0001
 * </pre>
 * 授权失败由 {@link ApiExceptionHandler} 统一转换为 403 + 可区分错误原因；
 * 版本相关失败转换为 400（INVALID_VERSION）/ 404（VERSION_NOT_FOUND）。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** 导出当前版本；带 version 参数时按历史版本追溯导出（审计复盘）。 */
    @GetMapping
    public ExportBundle export(@RequestParam String grantId,
                               @RequestParam String patientId,
                               @RequestParam(required = false) Set<String> categories,
                               @RequestParam(required = false) String version) {
        if (version != null && !version.isBlank()) {
            return exportService.exportVersion(grantId, patientId, categories, version);
        }
        return exportService.export(grantId, patientId, categories);
    }

    /** 版本历史：版本号、变化时间、相对上一版的变化清单（同一套授权校验）。 */
    @GetMapping("/versions")
    public List<VersionView> versions(@RequestParam String grantId,
                                      @RequestParam String patientId) {
        return exportService.listVersions(grantId, patientId);
    }
}
