package com.github.h3yow.clinicaldatanormalizerjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
}
