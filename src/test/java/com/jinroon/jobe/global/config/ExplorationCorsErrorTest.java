package com.jinroon.jobe.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 탐색형 API가 켜져 있을 때 오류 응답에도 CORS 헤더가 붙는지 확인한다.
 * 헤더가 없으면 브라우저가 응답 본문을 막아 프런트가 오류 code(401·400 등)를 구분할 수 없다.
 * 요청이 거부되는 경로만 호출하므로 DB에 데이터를 쓰지 않는다. 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다.
 */
@SpringBootTest(properties = {"exploration.enabled=true", "exploration.runtime.run-mode=DEV",
        "exploration.runtime.dev-user-ids=8200000001"})
@ActiveProfiles("exploration-dev")
@AutoConfigureMockMvc
class ExplorationCorsErrorTest {

    private static final String DEPLOYED_FRONT = "https://jinro-on.vercel.app";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedErrorCarriesAllowOrigin() throws Exception {
        mockMvc.perform(post("/api/exploration/v1/sessions")
                        .header("Origin", DEPLOYED_FRONT)
                        .header("Idempotency-Key", "cors-unauthenticated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diagnosisType\":\"EXPLORATION_V1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_FRONT))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void sharePreflightIsAllowed() throws Exception {
        // 공유 발급도 Idempotency-Key를 보내므로 preflight가 통과해야 한다.
        mockMvc.perform(options("/api/exploration/v1/results/1/share")
                        .header("Origin", DEPLOYED_FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_FRONT));
    }
}
