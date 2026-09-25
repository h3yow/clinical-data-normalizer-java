package com.github.h3yow.clinicaldatanormalizerjava.api;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.RejectionReason;
import com.github.h3yow.clinicaldatanormalizerjava.model.ConflictDecision;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.NormalizationService;
import com.github.h3yow.clinicaldatanormalizerjava.store.ObservationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量消息接收：逐条解析 + 归一化 + 幂等合并。
 * 单条失败不影响其他消息，失败按 RejectionReason 归类返回。
 */
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final Hl7Parser parser;
    private final NormalizationService normalizationService;
    private final ObservationStore store;

    public IngestController(Hl7Parser parser, NormalizationService normalizationService,
                            ObservationStore store) {
        this.parser = parser;
        this.normalizationService = normalizationService;
        this.store = store;
    }

    public record BatchRequest(List<String> messages) {
    }

    public record MessageOutcome(int index, String status, String reason,
                                 String detail, int observations, int conflicts) {
    }

    @PostMapping("/ingest/batch")
    public List<MessageOutcome> ingest(@RequestBody BatchRequest request) {
        List<MessageOutcome> outcomes = new ArrayList<>();
        int i = 0;
        for (String raw : request.messages()) {
            try {
                Hl7Message message = parser.parse(raw);
                List<Observation> observations = normalizationService.normalize(message);
                List<ConflictDecision> decisions = store.merge(observations);
                decisions.forEach(d -> log.info("conflict resolved: key={} rule={} winner={} loser={}",
                        d.conflictKey(), d.rule(),
                        d.winner().observationKey(), d.loser().observationKey()));
                outcomes.add(new MessageOutcome(i, "ACCEPTED", null, null,
                        observations.size(), decisions.size()));
            } catch (Hl7ParseException e) {
                RejectionReason reason = e.getReason();
                log.warn("message {} rejected: {} - {}", i, reason, e.getMessage());
                outcomes.add(new MessageOutcome(i, "REJECTED", reason.name(),
                        e.getMessage(), 0, 0));
            }
            i++;
        }
        return outcomes;
    }
}
