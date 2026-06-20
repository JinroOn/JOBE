package com.jinroon.jobe.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.ConsultationChatRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.response.ConsultationChatResponse;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class AiServiceClientTest {

    private final RestTemplate restTemplate = org.mockito.Mockito.mock(RestTemplate.class);
    private final AiServiceClient client = new AiServiceClient(restTemplate);

    @Test
    void recommendationCommentRequestAddsAuthAndRequestIdHeaders() {
        configureClient();
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "summary",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "컴퓨터공학과",
                        1,
                        87.5,
                        "strengths",
                        "weaknesses",
                        "reason"
                )),
                List.of("communicationScore"),
                "recommendation-v1.1",
                "response-request-id"
        );
        when(restTemplate.exchange(
                eq("http://ai.local/v1/recommendation/comment"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(RecommendationCommentResponse.class)
        )).thenReturn(ResponseEntity.ok(response));

        RecommendationCommentResponse actual = client.getRecommendationComment(recommendationRequest());

        assertThat(actual).isSameAs(response);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://ai.local/v1/recommendation/comment"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(RecommendationCommentResponse.class)
        );
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer internal-token");
        assertThat(headers.getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void weeklyPlanRequestReturnsNullWhenAiServiceCallFails() {
        configureClient();
        when(restTemplate.exchange(
                eq("http://ai.local/v1/plan/weekly"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(WeeklyPlanResponse.class)
        )).thenThrow(new ResourceAccessException("connection refused"));

        WeeklyPlanResponse actual = client.getWeeklyPlan(weeklyPlanRequest());

        assertThat(actual).isNull();
    }

    @Test
    void consultationChatRequestAddsAuthAndRequestIdHeaders() {
        configureClient();
        ConsultationChatResponse response = new ConsultationChatResponse(
                "answer",
                "consultation-chat-v1.0.0",
                "response-request-id"
        );
        when(restTemplate.exchange(
                eq("http://ai.local/v1/consultation/chat"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(ConsultationChatResponse.class)
        )).thenReturn(ResponseEntity.ok(response));

        ConsultationChatResponse actual = client.getConsultationChat(consultationChatRequest());

        assertThat(actual).isSameAs(response);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://ai.local/v1/consultation/chat"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(ConsultationChatResponse.class)
        );
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer internal-token");
        assertThat(headers.getFirst("X-Request-Id")).isNotBlank();
    }

    private void configureClient() {
        ReflectionTestUtils.setField(client, "aiServerUrl", "http://ai.local/");
        ReflectionTestUtils.setField(client, "aiServerToken", "internal-token");
    }

    private RecommendationCommentRequest recommendationRequest() {
        return new RecommendationCommentRequest(
                1L,
                profile(),
                List.of(new RecommendationCommentRequest.TopMajor(
                        "컴퓨터공학과",
                        1,
                        87.5,
                        null,
                        null,
                        null
                )),
                List.of(),
                null
        );
    }

    private WeeklyPlanRequest weeklyPlanRequest() {
        return new WeeklyPlanRequest(
                1L,
                new WeeklyPlanRequest.TargetMajor("컴퓨터공학과", 87.5, null),
                List.of("communicationScore"),
                new WeeklyPlanRequest.Profile(
                        80,
                        70,
                        90,
                        85,
                        75,
                        88,
                        60,
                        65,
                        72
                ),
                new WeeklyPlanRequest.Constraints(12, 8, "practice-first")
        );
    }

    private ConsultationChatRequest consultationChatRequest() {
        return new ConsultationChatRequest(
                1L,
                7L,
                "question",
                List.of(new ConsultationChatRequest.HistoryMessage("user", "question")),
                false,
                null
        );
    }

    private RecommendationCommentRequest.Profile profile() {
        return new RecommendationCommentRequest.Profile(
                80,
                70,
                90,
                85,
                75,
                88,
                60,
                65,
                72
        );
    }
}
