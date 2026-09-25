package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.api.ExportController;
import com.github.h3yow.clinicaldatanormalizerjava.api.IngestController;
import com.github.h3yow.clinicaldatanormalizerjava.consent.Consent;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：批量投递（含畸形消息与重复投递）-> 授权导出。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String validMsg(String msgId, String source, String obxSet,
                                   String value, String unit) {
        return "MSH|^~\\&|" + source + "|L|HIS|H|20240101120000||ORU^R01|" + msgId + "|P|2.5\r"
                + "PID|1||P999^^^HIS^MR||Doe^Jane\r"
                + "OBX|" + obxSet + "|NM|GLU^Glucose^L|1|" + value + "|" + unit
                + "|||||F|||20240101113000";
    }

    @Test
    void batchIngestReplayAndAuthorizedExport() {
        String good = validMsg("E2E-1", "LAB", "1", "5.5", "mmol/L");
        String badUnit = validMsg("E2E-2", "LAB", "1", "5.5", "stones");
        String badValue = validMsg("E2E-3", "LAB", "1", "abc", "mmol/L");
        String malformed = "PID|1||P999";

        // 第一次批量：含畸形消息，逐条归类
        ResponseEntity<List<IngestController.MessageOutcome>> r1 = postBatch(
                List.of(good, badUnit, badValue, malformed));
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        List<IngestController.MessageOutcome> outcomes = r1.getBody();
        assertNotNull(outcomes);
        outcomes.forEach(o -> log.info("outcome idx={} status={} reason={} detail={}",
                o.index(), o.status(), o.reason(), o.detail()));
        assertEquals("ACCEPTED", outcomes.get(0).status());
        assertEquals("REJECTED", outcomes.get(1).status());
        assertEquals("UNKNOWN_UNIT", outcomes.get(1).reason());
        assertEquals("INVALID_VALUE", outcomes.get(2).reason());
        assertEquals("MALFORMED_STRUCTURE", outcomes.get(3).reason());

        // 重放同一消息：幂等，观测数不增
        ResponseEntity<List<IngestController.MessageOutcome>> r2 = postBatch(List.of(good, good));
        List<IngestController.MessageOutcome> replay = r2.getBody();
        assertNotNull(replay);
        replay.forEach(o -> log.info("replay outcome idx={} status={} obs={} conflicts={}",
                o.index(), o.status(), o.observations(), o.conflicts()));

        // 未授权导出 -> 403 NO_CONSENT
        ResponseEntity<ExportController.ErrorResponse> denied = rest.exchange(
                url("/export"), HttpMethod.POST,
                new HttpEntity<>(new ExportController.ExportRequest("P999", Set.of("2345-7"))),
                ExportController.ErrorResponse.class);
        log.info("unauthorized export: status={} kind={}", denied.getStatusCode(),
                denied.getBody() != null ? denied.getBody().kind() : null);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("NO_CONSENT", denied.getBody().kind());

        // 登记授权（范围仅血糖）后导出
        rest.postForEntity(url("/consents"),
                new Consent("P999", Set.of("2345-7"),
                        Instant.now().plus(1, ChronoUnit.DAYS)), Void.class);

        ResponseEntity<ExportResult> e1 = rest.exchange(url("/export"), HttpMethod.POST,
                new HttpEntity<>(new ExportController.ExportRequest("P999", Set.of("2345-7"))),
                ExportResult.class);
        ResponseEntity<ExportResult> e2 = rest.exchange(url("/export"), HttpMethod.POST,
                new HttpEntity<>(new ExportController.ExportRequest("P999", Set.of("2345-7"))),
                ExportResult.class);
        assertEquals(HttpStatus.OK, e1.getStatusCode());
        assertNotNull(e1.getBody());
        e1.getBody().records().forEach(rec -> log.info("exported record: {}", rec));
        log.info("digest1={} digest2={}", e1.getBody().digest(), e2.getBody().digest());

        // 重放未产生重复观测：导出只有 1 条记录
        assertEquals(1, e1.getBody().records().size());
        // 同一数据版本 + 同一授权范围 -> 摘要可复现
        assertEquals(e1.getBody().digest(), e2.getBody().digest());
        // 不含直接标识符
        assertFalse(e1.getBody().records().get(0).contains("P999"));

        // 越权导出（请求授权范围外指标）-> 403 OUT_OF_SCOPE
        ResponseEntity<ExportController.ErrorResponse> outOfScope = rest.exchange(
                url("/export"), HttpMethod.POST,
                new HttpEntity<>(new ExportController.ExportRequest("P999",
                        Set.of("2345-7", "8480-6"))),
                ExportController.ErrorResponse.class);
        log.info("out-of-scope export: status={} kind={}", outOfScope.getStatusCode(),
                outOfScope.getBody() != null ? outOfScope.getBody().kind() : null);
        assertEquals(HttpStatus.FORBIDDEN, outOfScope.getStatusCode());
        assertEquals("OUT_OF_SCOPE", outOfScope.getBody().kind());
    }

    private ResponseEntity<List<IngestController.MessageOutcome>> postBatch(List<String> msgs) {
        return rest.exchange(url("/ingest/batch"), HttpMethod.POST,
                new HttpEntity<>(new IngestController.BatchRequest(msgs)),
                new ParameterizedTypeReference<>() {
                });
    }
}
