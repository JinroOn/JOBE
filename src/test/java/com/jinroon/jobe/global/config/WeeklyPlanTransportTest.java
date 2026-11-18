package com.jinroon.jobe.global.config;

import com.jinroon.jobe.global.client.AiServiceClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeeklyPlanTransportTest {
    @Test void slowWeeklyReplyUsesDedicatedTransportWhileOrdinaryRequestsKeepShortBudget()throws Exception{
        var config=new RestTemplateConfig();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{Thread.sleep(250);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            byte[] body="{\"planId\":\"test\",\"overview\":\"test\",\"weeklyPlan\":[],\"riskNotes\":[],\"version\":\"test\",\"requestId\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            try{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}
        });server.start();
        try{
            var client=new AiServiceClient(config.restTemplate(1000,100),config.weeklyPlanRestTemplate(2000,40000));
            ReflectionTestUtils.setField(client,"aiServerUrl","http://127.0.0.1:"+server.getAddress().getPort());
            ReflectionTestUtils.setField(client,"aiServerToken","SYNTHETIC_TEST_TOKEN");
            assertThat(client.getWeeklyPlan(null).planId()).isEqualTo("test");
            assertThat(client.getRecommendationComment(null)).isNull();
        }finally{server.stop(0);}
    }

    @Test void timeoutIsDistinctAndDoesNotExposeExceptionPayload(){
        var weekly=mock(RestTemplate.class);
        when(weekly.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse.class)))
            .thenThrow(new ResourceAccessException("PRIVATE_BODY_AND_TOKEN",new SocketTimeoutException("PRIVATE_DETAIL")));
        var client=new AiServiceClient(mock(RestTemplate.class),weekly);
        ReflectionTestUtils.setField(client,"aiServerUrl","http://127.0.0.1");
        assertThatThrownBy(()->client.getWeeklyPlan(null)).isInstanceOf(AiServiceClient.WeeklyPlanFailure.class)
            .hasMessage("AI_WEEKLY_PLAN_TIMEOUT").hasNoCause();
        verify(weekly,times(1)).exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse.class));
    }

    @Test void weeklyBudgetCannotBeBelowProviderBudgetOrUnbounded(){
        var config=new RestTemplateConfig();
        assertThatThrownBy(()->config.weeklyPlanRestTemplate(2000,10000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->config.weeklyPlanRestTemplate(2000,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->config.weeklyPlanRestTemplate(2000,60000)).isInstanceOf(IllegalArgumentException.class);
    }
}
