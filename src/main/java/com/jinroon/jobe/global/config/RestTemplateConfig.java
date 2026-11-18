package com.jinroon.jobe.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(
            @Value("${ai.server.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ai.server.read-timeout-ms:10000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /** 30s plan call + transport/evidence margin, below the unchanged 60s job lease. */
    @Bean("explorationModelRestTemplate")
    public RestTemplate explorationModelRestTemplate(
            @Value("${exploration.jobs.model-connect-timeout-ms:2000}") int connect,
            @Value("${exploration.jobs.model-read-timeout-ms:40000}") int read) {
        if(connect<1 || connect>2000 || read<35000 || read>40000)
            throw new IllegalArgumentException("Invalid exploration model transport time budget");
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);factory.setReadTimeout(read);
        return new RestTemplate(factory);
    }

    /** Legacy weekly plans have a separate bounded budget; ordinary AI calls keep their existing timeout. */
    @Bean("weeklyPlanRestTemplate")
    public RestTemplate weeklyPlanRestTemplate(
            @Value("${ai.server.weekly-plan-connect-timeout-ms:2000}") int connect,
            @Value("${ai.server.weekly-plan-read-timeout-ms:40000}") int read) {
        if(connect<1 || connect>2000 || read<35000 || read>45000)
            throw new IllegalArgumentException("Invalid weekly plan transport time budget");
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);factory.setReadTimeout(read);
        return new RestTemplate(factory);
    }
}
