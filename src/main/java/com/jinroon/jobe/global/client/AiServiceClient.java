package com.jinroon.jobe.global.client;

import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.ConsultationChatRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.response.ConsultationChatResponse;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import java.net.SocketTimeoutException;

@Slf4j
@Component
public class AiServiceClient {
    private static final String RECOMMENDATION_COMMENT_ENDPOINT = "/v1/recommendation/comment";
    private static final String WEEKLY_PLAN_ENDPOINT = "/v1/plan/weekly";
    private static final String CONSULTATION_CHAT_ENDPOINT = "/v1/consultation/chat";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestTemplate restTemplate;
    private final RestTemplate weeklyPlanRestTemplate;

    public AiServiceClient(@Qualifier("restTemplate") RestTemplate restTemplate,
                           @Qualifier("weeklyPlanRestTemplate") RestTemplate weeklyPlanRestTemplate) {
        this.restTemplate=restTemplate;this.weeklyPlanRestTemplate=weeklyPlanRestTemplate;
    }

    public static class WeeklyPlanFailure extends RuntimeException {
        private final String code;
        public WeeklyPlanFailure(String code){super(code);this.code=code;}
        public String code(){return code;}
    }

    @Value("${ai.server.url}")
    private String aiServerUrl;

    @Value("${ai.server.token}")
    private String aiServerToken;

    public RecommendationCommentResponse getRecommendationComment(RecommendationCommentRequest request) {
        return post(RECOMMENDATION_COMMENT_ENDPOINT, request, RecommendationCommentResponse.class);
    }

    public WeeklyPlanResponse getWeeklyPlan(WeeklyPlanRequest request) {
        return post(weeklyPlanRestTemplate,WEEKLY_PLAN_ENDPOINT, request, WeeklyPlanResponse.class,true);
    }

    public ConsultationChatResponse getConsultationChat(ConsultationChatRequest request) {
        return post(CONSULTATION_CHAT_ENDPOINT, request, ConsultationChatResponse.class);
    }

    private <T, R> R post(String endpoint, T body, Class<R> responseType) {
        return post(restTemplate,endpoint,body,responseType,false);
    }

    private <T,R> R post(RestTemplate transport,String endpoint,T body,Class<R> responseType,boolean weekly) {
        String requestId = UUID.randomUUID().toString();
        try {
            ResponseEntity<R> response = transport.exchange(
                    url(endpoint),
                    HttpMethod.POST,
                    buildRequest(body, requestId),
                    responseType
            );
            log.info("AI request success endpoint={} requestId={} status={}",
                    endpoint, requestId, response.getStatusCode().value());
            if(weekly && response.getBody()==null)throw new WeeklyPlanFailure("AI_WEEKLY_PLAN_EMPTY_RESPONSE");
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            log.warn("AI request failed endpoint={} requestId={} status={} errorType={}",
                    endpoint, requestId, e.getStatusCode().value(), e.getClass().getSimpleName());
            if(weekly)throw new WeeklyPlanFailure("AI_WEEKLY_PLAN_HTTP_"+e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("AI request failed endpoint={} requestId={} errorType={}",
                    endpoint, requestId, e.getClass().getSimpleName());
            if(weekly){
                boolean timeout=false;for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException)timeout=true;
                throw new WeeklyPlanFailure(timeout?"AI_WEEKLY_PLAN_TIMEOUT":e instanceof ResourceAccessException?"AI_WEEKLY_PLAN_CONNECTION_FAILED":"AI_WEEKLY_PLAN_INVALID_RESPONSE");
            }
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
