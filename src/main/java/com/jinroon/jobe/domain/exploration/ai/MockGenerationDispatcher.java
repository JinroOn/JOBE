package com.jinroon.jobe.domain.exploration.ai;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Bounded serial batches per instance; DB leases arbitrate competing instances. */
@Service
@RequiredArgsConstructor
public class MockGenerationDispatcher {
    private final OwnedGenerationStore store;
    private final OwnedMockExecutionService execution;
    @Value("${exploration.jobs.dispatch.enabled:false}") private boolean enabled;
    @Scheduled(fixedDelayString="${exploration.jobs.dispatch.delay-ms:5000}",initialDelayString="${exploration.jobs.dispatch.initial-delay-ms:5000}")
    public void scheduledDrain(){if(enabled)try{drain();}catch(RuntimeException ignored){/* DB unavailable: keep durable work and try a later tick. */}}
    @Scheduled(fixedDelayString="${exploration.jobs.dispatch.delay-ms:5000}",initialDelayString="${exploration.jobs.dispatch.initial-delay-ms:5000}")
    public void scheduledExplanationDrain() {
        if (!execution.readyForExplanationDispatch()) return;
        try {
            store.exhaustExpired();
            for (var task : store.explanationCandidates()) {
                try {
                    execution.executeQueuedExplanation(task);
                } catch (RuntimeException ignored) {
                    // The durable task tracks the attempt. Do not log personal inputs.
                }
            }
        } catch (RuntimeException ignored) {
            // A later tick can recover from temporary database unavailability.
        }
    }
    @Transactional(propagation=Propagation.NEVER)
    public int drain(){
        if(!enabled || !execution.readyForDispatch())return 0;
        store.exhaustExpired();int visited=0;
        for(var task:store.candidates(10)){
            try{execution.executeQueued(task);}
            catch(RuntimeException ignored){/* Durable state/lease handles failure; no raw inputs/errors logged. */}
            visited++;
        }
        return visited;
    }
}
