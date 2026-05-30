package com.jinroon.jobe.global.client;

import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final RestTemplate restTemplate;

    @Value("${ai.server.url}")
    private String aiServerUrl;

    @Value("${ai.server.token}")
    private String aiServerToken;

    public RecommendationCommentResponse getRecommendationComment(RecommendationCommentRequest request) {
        try {
            return restTemplate.postForObject(
                    aiServerUrl + "/v1/recommendation/comment",
                    buildRequest(request),
                    RecommendationCommentResponse.class
            );
        } catch (RestClientException e) {
            log.warn("AI 서버 추천 코멘트 요청 실패: {}", e.getMessage());
            return null;
        }
    }

    public WeeklyPlanResponse getWeeklyPlan(WeeklyPlanRequest request) {
        try {
            return restTemplate.postForObject(
                    aiServerUrl + "/v1/plan/weekly",
                    buildRequest(request),
                    WeeklyPlanResponse.class
            );
        } catch (RestClientException e) {
            log.warn("AI 서버 주간 계획 요청 실패: {}", e.getMessage());
            return null;
        }
    }

    private <T> HttpEntity<T> buildRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(aiServerToken);
        return new HttpEntity<>(body, headers);
    }
}
