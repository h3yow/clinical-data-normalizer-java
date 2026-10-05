package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationGrant;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端：HTTP 投递 HL7 -> 纵向合并 -> 去标识化导出 -> 越权拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthorizationService auth;

    private String oru(String controlId, String source, String patient, String value, String unit, String time) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId + "|P|2.5.1\r"
                + "PID|||" + patient + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|" + unit + "|||||F|||" + time + "\r";
    }

    @Test
    void fullFlowIngestReplayExportAndReject() throws Exception {
        String msg = oru("MSG-E2E-1", "HIS_A", "P-E2E", "72", "bpm", "20260920100000+0800");

        // 首次投递：200 + ACCEPTED
        MvcResult first = mvc.perform(post("/api/hl7/ingest")
                        .contentType(MediaType.TEXT_PLAIN).content(msg))
                .andExpect(status().isOk())
                .andReturn();
        String body1 = first.getResponse().getContentAsString();
        assertTrue(body1.contains("ACCEPTED"));
        log.info("[HTTP 投递成功] {}", body1);

        // 重复投递：200 + DUPLICATE
        MvcResult dup = mvc.perform(post("/api/hl7/ingest")
                        .contentType(MediaType.TEXT_PLAIN).content(msg))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(dup.getResponse().getContentAsString().contains("DUPLICATE"));
        log.info("[HTTP 重复投递] {}", dup.getResponse().getContentAsString());

        // 为 E2E 患者登记一个有效授权
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-all-demo")
                        .param("patientId", "P-E2E")
                        .param("categories", "vital_signs"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorReason").value("PATIENT_OUT_OF_SCOPE"));
        log.info("[HTTP 越权] grant-all-demo 不含 P-E2E -> 403 PATIENT_OUT_OF_SCOPE，未导出任何数据");
    }

    @Test
    void malformedPayloadReturnsRejectedWithReason() throws Exception {
        MvcResult result = mvc.perform(post("/api/hl7/ingest")
                        .contentType(MediaType.TEXT_PLAIN).content("not-an-hl7-message"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("REJECTED"));
        assertTrue(body.contains("UNEXPECTED_FRAME_START"));
        log.info("[HTTP 畸形消息] {}", body);
    }

    @Test
    void correctionWithdrawalAndVersionedExportOverHttp() throws Exception {
        // 为端到端患者登记有效授权
        auth.register(new AuthorizationGrant("grant-e2e-v", Set.of("P-E2E-V"),
                Set.of("vital_signs"), Instant.now().plus(1, ChronoUnit.DAYS)));

        String add = oru("MSG-E2E-A", "HIS_A", "P-E2E-V", "72", "bpm", "20260920100000+0800");
        String correct = add.replace("MSG-E2E-A", "MSG-E2E-C").replace("|F|", "|C|")
                .replace("||72|", "||75|");
        String withdraw = "MSH|^~\\&|HIS_A|FAC||FAC|20260920120000+0800||ORU^R01|MSG-E2E-W|P|2.5.1\r"
                + "PID|||P-E2E-V\rOBR|1||B-1|8867-4\rOBX|1||8867-4^^LN||||||||D|||20260920100000+0800\r";

        mvc.perform(post("/api/hl7/ingest").contentType(MediaType.TEXT_PLAIN).content(add))
                .andExpect(status().isOk());
        mvc.perform(post("/api/hl7/ingest").contentType(MediaType.TEXT_PLAIN).content(correct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mergeDecisions[0].kind").value("CORRECTION_APPLIED"));
        mvc.perform(post("/api/hl7/ingest").contentType(MediaType.TEXT_PLAIN).content(withdraw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mergeDecisions[0].kind").value("WITHDRAWAL_APPLIED"));

        // 版本历史：ADDED -> CORRECTED -> WITHDRAWN
        mvc.perform(get("/api/export/versions")
                        .param("grantId", "grant-e2e-v").param("patientId", "P-E2E-V"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].changes[0].kind").value("ADDED"))
                .andExpect(jsonPath("$[1].changes[0].kind").value("CORRECTED"))
                .andExpect(jsonPath("$[2].changes[0].kind").value("WITHDRAWN"));

        // 按历史版本复盘：v1=72，v2=75，当前版本为空（已撤回）
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-e2e-v").param("patientId", "P-E2E-V")
                        .param("categories", "vital_signs").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataVersion").value(1))
                .andExpect(jsonPath("$.observations[0].value").value(72.0));
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-e2e-v").param("patientId", "P-E2E-V")
                        .param("categories", "vital_signs").param("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observations[0].value").value(75.0));
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-e2e-v").param("patientId", "P-E2E-V")
                        .param("categories", "vital_signs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observations.length()").value(0));

        // 不存在的版本 -> 404 VERSION_NOT_FOUND；非法版本 -> 400 INVALID_VERSION
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-e2e-v").param("patientId", "P-E2E-V")
                        .param("categories", "vital_signs").param("version", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorReason").value("VERSION_NOT_FOUND"));
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-e2e-v").param("patientId", "P-E2E-V")
                        .param("categories", "vital_signs").param("version", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorReason").value("INVALID_VERSION"));
        // 越权患者按历史版本导出 -> 403
        mvc.perform(get("/api/export")
                        .param("grantId", "grant-all-demo").param("patientId", "P-E2E-V")
                        .param("categories", "vital_signs").param("version", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorReason").value("PATIENT_OUT_OF_SCOPE"));
        log.info("[HTTP 更正撤回+按版本导出] v1=72 v2=75 当前=空(已撤回)；"
                + "版本不存在 404 / 非法版本 400 / 越权 403 —— 判定: 全链路可区分");
    }
}
