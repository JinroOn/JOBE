package com.jinroon.jobe.global.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS 허용 출처 제한 테스트(문서 4.4).
 * 기본 설정(app.cors.allowed-origins)의 배포 주소·로컬 개발 주소만 허용하고, 그 외 출처와 credentials는 허용하지 않는다.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

    private static final String DEPLOYED_FRONT = "https://jinro-on.vercel.app";
    private static final String LOCAL_DEV = "http://localhost:5173";
    private static final String OTHER_SITE = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromDeployedFrontIsAllowed() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", DEPLOYED_FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_FRONT))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void preflightWithAuthorizationHeaderIsAllowed() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", LOCAL_DEV)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_DEV));
    }

    @Test
    void simpleRequestFromDeployedFrontGetsAllowOriginHeader() throws Exception {
        mockMvc.perform(get("/api/majors").header("Origin", DEPLOYED_FRONT))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_FRONT));
    }

    @Test
    void preflightFromOtherSiteIsRejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", OTHER_SITE)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void requestFromOtherSiteIsRejected() throws Exception {
        mockMvc.perform(get("/api/majors").header("Origin", OTHER_SITE))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void explorationMutationPreflightAllowsIdempotencyKey() throws Exception {
        // 탐색형 변경 요청은 Authorization·Idempotency-Key를 함께 보내므로 브라우저가 먼저 preflight를 보낸다.
        mockMvc.perform(options("/api/exploration/v1/sessions")
                        .header("Origin", DEPLOYED_FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_FRONT))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("Idempotency-Key")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void explorationAnswerPreflightAllowsPut() throws Exception {
        mockMvc.perform(options("/api/exploration/v1/sessions/1/answers/1")
                        .header("Origin", LOCAL_DEV)
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_DEV))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PUT")));
    }

    @Test
    void explorationPreflightFromOtherSiteIsRejected() throws Exception {
        mockMvc.perform(options("/api/exploration/v1/sessions")
                        .header("Origin", OTHER_SITE)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightDoesNotAllowUnlistedHeader() throws Exception {
        // 일부만 허용 헤더면 서버는 허용 헤더만 돌려준다. 빠진 헤더가 있으므로 브라우저가 실제 요청을 막는다.
        mockMvc.perform(options("/api/exploration/v1/sessions")
                        .header("Origin", DEPLOYED_FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key, X-Unlisted-Header"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("Idempotency-Key")))
                .andExpect(header().string("Access-Control-Allow-Headers", not(containsStringIgnoringCase("X-Unlisted-Header"))));
    }

    @Test
    void preflightWithOnlyUnlistedHeaderIsRejected() throws Exception {
        mockMvc.perform(options("/api/exploration/v1/sessions")
                        .header("Origin", DEPLOYED_FRONT)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Unlisted-Header"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void disabledExplorationErrorIsReadableFromAllowedOrigin() throws Exception {
        // 기능이 꺼져 있을 때의 404도 브라우저가 오류 본문을 읽을 수 있어야 한다.
        mockMvc.perform(get("/api/exploration/v1/sessions").header("Origin", DEPLOYED_FRONT))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", DEPLOYED_FRONT));
    }

    @Test
    void requestWithoutOriginIsUnaffected() throws Exception {
        // 서버 간 호출·curl 등 Origin이 없는 요청은 CORS 대상이 아니다.
        mockMvc.perform(get("/api/majors")).andExpect(status().isOk());
    }
}
