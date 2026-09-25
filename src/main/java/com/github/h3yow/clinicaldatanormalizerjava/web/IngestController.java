package com.github.h3yow.clinicaldatanormalizerjava.web;

import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionOutcome;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HL7 v2 批量投递入口。请求体为原始 HL7 文本（CR/LF 段结束，可含 FHS/BHS 信封）。
 */
@RestController
@RequestMapping("/api/hl7")
public class IngestController {

    private final IngestionService ingestionService;

    public IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(path = "/ingest", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE})
    public List<IngestionOutcome> ingest(@RequestBody String body) {
        return ingestionService.ingest(body.getBytes(StandardCharsets.UTF_8));
    }
}
