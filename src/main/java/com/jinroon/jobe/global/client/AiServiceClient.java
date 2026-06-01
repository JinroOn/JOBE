package com.jinroon.jobe.global.client;

import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {
    private static final String RECOMMENDATION_COMMENT_ENDPOINT = "/v1/recommendation/comment";
    private static final String WEEKLY_PLAN_ENDPOINT = "/v1/plan/weekly";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestTemplate restTemplate;

    @Value("${ai.server.url}")
    private String aiServerUrl;

    @Value("${ai.server.token}")
    private String aiServerToken;

    public RecommendationCommentResponse getRecommendationComment(RecommendationCommentRequest request) {
        return post(RECOMMENDATION_COMMENT_ENDPOINT, request, RecommendationCommentResponse.class);
    }

    public WeeklyPlanResponse getWeeklyPlan(WeeklyPlanRequest request) {
        return post(WEEKLY_PLAN_ENDPOINT, request, WeeklyPlanResponse.class);
    }

    private <T, R> R post(String endpoint, T body, Class<R> responseType) {
        String requestId = UUID.randomUUID().toString();
        try {
            ResponseEntity<R> response = restTemplate.exchange(
                    url(endpoint),
                    HttpMethod.POST,
                    buildRequest(body, requestId),
                    responseType
            );
            log.info("AI request success endpoint={} requestId={} status={}",
                    endpoint, requestId, response.getStatusCode().value());
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            log.warn("AI request failed endpoint={} requestId={} status={} errorType={} message={}",
                    endpoint, requestId, e.getStatusCode().value(), e.getClass().getSimpleName(), e.getMessage());
        } catch (RestClientException e) {
            log.warn("AI request failed endpoint={} requestId={} errorType={} message={}",
                    endpoint, requestId, e.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    private String url(String endpoint) {
        String baseUrl = aiServerUrl == null ? "" : aiServerUrl.replaceAll("/+$", "");
        return baseUrl + endpoint;
    }

    private <T> HttpEntity<T> buildRequest(T body, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(aiServerToken == null ? "" : aiServerToken);
        headers.set(REQUEST_ID_HEADER, requestId);
        return new HttpEntity<>(body, headers);
    }
}
