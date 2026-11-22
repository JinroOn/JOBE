package com.jinroon.jobe.domain.exploration.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** At-least-once erasure delivery. Never holds MySQL locks during remote calls. */
@Service
@RequiredArgsConstructor
public class ExplorationAiErasureService {
    private final JdbcTemplate jdbc;
    private final ExplorationJobClient client;
    @Value("${exploration.jobs.erasure.enabled:false}") private boolean enabled;
    @Scheduled(fixedDelayString="${exploration.jobs.erasure.delay-ms:60000}")
    public void scheduledDrain(){if(enabled)drain();}
    @Transactional(propagation=Propagation.NEVER)
    public int drain(){
        int delivered=0;
        for(String owner:jdbc.queryForList("SELECT owner_key FROM exploration_ai_erasure_outbox WHERE next_attempt_at<=NOW(6) ORDER BY created_at,owner_key LIMIT 25",String.class)){
            try{client.purge(owner);}catch(ExplorationJobClient.Unavailable e){
                jdbc.update("UPDATE exploration_ai_erasure_outbox SET attempt_count=LEAST(attempt_count+1,1000000),next_attempt_at=DATE_ADD(NOW(6),INTERVAL 60 SECOND) WHERE owner_key=?",owner);
                continue;
            }
            delivered+=jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",owner);
        }
        return delivered;
    }
    @Configuration
    @EnableScheduling
    static class Scheduling {
        @org.springframework.context.annotation.Bean
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler(){
            var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("exploration-lifecycle-");return scheduler;
        }
    }
}
