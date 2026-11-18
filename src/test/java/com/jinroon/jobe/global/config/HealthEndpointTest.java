package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배포 헬스체크(/actuator/health)는 앱이 요청을 받을 수 있는지만 본다.
 * 메일 서버 인증 실패(예: 앱 비밀번호 만료, 로컬 SMTP 미설정)로 전체 상태가 DOWN이 되지 않아야 한다.
 * 테스트 환경에는 메일 비밀번호가 없으므로 메일 점검이 켜져 있으면 503이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthIsUpWithoutWorkingMailServer() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void mailHealthIndicatorIsNotRegistered() {
        assertThat(context.getBeanNamesForType(MailHealthIndicator.class)).isEmpty();
    }
}
