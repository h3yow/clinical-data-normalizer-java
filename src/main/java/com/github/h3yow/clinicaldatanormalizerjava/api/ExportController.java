package com.github.h3yow.clinicaldatanormalizerjava.api;

import com.github.h3yow.clinicaldatanormalizerjava.consent.Consent;
import com.github.h3yow.clinicaldatanormalizerjava.consent.ConsentException;
import com.github.h3yow.clinicaldatanormalizerjava.consent.ConsentService;
import com.github.h3yow.clinicaldatanormalizerjava.export.DeidExporter;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportResult;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.store.ObservationStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 去标识化导出：先过授权校验（最小必要披露），再导出。
 */
@RestController
public class ExportController {

    private final ObservationStore store;
    private final ConsentService consentService;
    private final DeidExporter exporter;

    public ExportController(ObservationStore store, ConsentService consentService,
                            DeidExporter exporter) {
        this.store = store;
        this.consentService = consentService;
        this.exporter = exporter;
    }

    public record ExportRequest(String patientId, Set<String> codes) {
    }

    public record ErrorResponse(String kind, String detail) {
    }

    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody ExportRequest request) {
        try {
            consentService.requireCovered(request.patientId(), request.codes(), Instant.now());
        } catch (ConsentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse(e.getKind().name(), e.getMessage()));
        }
        List<Observation> all = store.observationsOf(request.patientId());
        List<Observation> scoped = request.codes().contains("*") ? all
                : all.stream().filter(o -> request.codes().contains(o.code())).toList();
        String scopeKey = request.patientId() + "|"
                + String.join(",", request.codes().stream().sorted().toList());
        ExportResult result = exporter.export(scoped, scopeKey, store.version());
        return ResponseEntity.ok(result);
    }

    /** 登记授权（演示/本地运行用） */
    @PostMapping("/consents")
    public ResponseEntity<Void> registerConsent(@RequestBody Consent consent) {
        consentService.register(consent);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
