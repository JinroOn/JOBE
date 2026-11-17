package com.jinroon.jobe.domain.exploration.ai;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Explicit MOCK-only execution. Java owns the last account/context/cancel/lease decision. */
@Service
@RequiredArgsConstructor
public class OwnedMockExecutionService {
    private final ExplorationJobService jobs;
    private final ExplorationJobOwnerService owners;
    private final OwnedGenerationStore store;
    private final ExplorationJobClient client;
    @Value("${exploration.jobs.mock-enabled:false}") private boolean enabled;
    @Value("${exploration.jobs.erasure.enabled:false}") private boolean erasureEnabled;
    @Value("${exploration.enabled:false}") private boolean explorationEnabled;
    @Value("${exploration.runtime.run-mode:PUBLIC}") private String runMode;
    public boolean readyForDispatch(){return enabled && erasureEnabled && explorationEnabled && "DEV".equals(runMode) && client.readyForMock();}
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode execute(long user,long context,String purpose,String mutation){
        if(!enabled)throw new ExplorationJobClient.Unavailable();
        JsonNode queued=jobs.command(user,context,purpose,mutation,"enqueue");
        if(queued.path("status").asText().equals("INSUFFICIENT_EVIDENCE"))return queued;
        var existing=store.completed(user,context,purpose,mutation);if(existing!=null)return existing;
        var lease=store.claim(user,context,purpose,mutation);
        return compute(new OwnedGenerationStore.Candidate(user,context,purpose,mutation),lease,false);
    }
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode executeQueued(OwnedGenerationStore.Candidate task){
        if(!readyForDispatch())throw new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.CONFIGURATION);
        OwnedGenerationStore.Lease lease;
        try{lease=store.claim(task.user(),task.context(),task.purpose(),task.mutation());}
        catch(org.springframework.dao.TransientDataAccessException | org.springframework.dao.DataAccessResourceFailureException | org.springframework.transaction.TransactionException e){throw e;}
        catch(RuntimeException e){store.rejectUnclaimed(task);throw e;}
        return compute(task,lease,true);
    }
    private JsonNode compute(OwnedGenerationStore.Candidate task,OwnedGenerationStore.Lease lease,boolean enqueue){
        try{
            String owner=owners.ownedKey(task.user());
            if(enqueue){
                var queued=client.command("enqueue",owner,task.mutation(),lease.request());
                if(java.util.Set.of("INSUFFICIENT_EVIDENCE","BLOCKED","CANCELLED").contains(queued.path("status").asText()))throw new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.EVIDENCE_UNAVAILABLE);
            }
            var result=client.executeMock(owner,task.mutation(),lease.request());
            return store.completeMock(task.user(),task.context(),task.purpose(),task.mutation(),lease.token(),result);
        }catch(ExplorationJobClient.Unavailable e){store.failed(task,lease.token(),e.retryable(),e.failure().name());throw e;}
        catch(com.jinroon.jobe.domain.exploration.ai.ExplorationAiContract.Invalid e){store.failed(task,lease.token(),false,"OUTPUT_INVALID");throw e;}
        catch(org.springframework.dao.TransientDataAccessException | org.springframework.dao.DataAccessResourceFailureException | org.springframework.transaction.TransactionException e){store.failed(task,lease.token(),true,"TRANSIENT");throw e;}
        catch(com.jinroon.jobe.domain.exploration.api.ExplorationApiException e){store.failed(task,lease.token(),false,"SOURCE_UNAVAILABLE");throw e;}
        catch(RuntimeException e){store.failed(task,lease.token(),false,"UNEXPECTED_FAILURE");throw e;}
    }
}
