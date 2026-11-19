package com.jinroon.jobe.domain.exploration.ai;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Compatibility-named coordinator for separate MOCK/MODEL paths. Java owns final authorization. */
@Service
@RequiredArgsConstructor
public class OwnedMockExecutionService {
    private final ExplorationJobService jobs;
    private final ExplorationJobOwnerService owners;
    private final OwnedGenerationStore store;
    private final ExplorationJobClient client;
    private final ExplorationModelAvailability availability;
    @Value("${exploration.jobs.mock-enabled:false}") private boolean enabled;
    private final com.jinroon.jobe.domain.exploration.context.ResultContextService contexts;
    private final ExplorationAiInputAdapter adapter;
    public void requireModelEnabled(){
        if (!availability.modelExecutionEnabled()) throw new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.CONFIGURATION);
    }
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode executePlan(long user,long context,String mutation,JsonNode request){
        requireModelEnabled();
        if(!ExplorationAiContract.PLAN12_VERSION.equals(request.path("contractVersion").asText())||!mutation.equals(request.path("requestId").asText()))throw new ExplorationJobClient.Conflict();
        store.reserveModel(user,context,"PLAN",mutation,request);
        var existing=store.completedModel(user,context,"PLAN",mutation);if(existing!=null)return existing;
        var lease=store.claimModel(user,context,"PLAN",mutation);
        return compute(new OwnedGenerationStore.Candidate(user,context,"PLAN",mutation),lease,true,true);
    }
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode executeConversation(long user,long context,String mutation,JsonNode request){
        requireModelEnabled();
        if(!ExplorationAiContract.CONVERSATION_VERSION.equals(request.path("contractVersion").asText())||!mutation.equals(request.path("requestId").asText()))throw new ExplorationJobClient.Conflict();
        store.reserveModel(user,context,"CONSULTATION",mutation,request);
        var existing=store.completedModel(user,context,"CONSULTATION",mutation);if(existing!=null)return existing;
        var lease=store.claimModel(user,context,"CONSULTATION",mutation);
        return compute(new OwnedGenerationStore.Candidate(user,context,"CONSULTATION",mutation),lease,true,true);
    }
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode executeModel(long user,long context,String purpose,String mutation){
        requireModelEnabled();
        if(mutation==null || !mutation.matches("[A-Za-z0-9_-]{1,80}"))throw new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.INVALID_REQUEST);
        var snapshot=contexts.readForAdapter(user,context,purpose).snapshot();String owner=owners.ownedKey(user);
        String id=java.util.UUID.nameUUIDFromBytes((owner+":"+mutation).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var request=adapter.build(snapshot,id);store.reserveModel(user,context,purpose,mutation,request);
        var existing=store.completedModel(user,context,purpose,mutation);if(existing!=null)return existing;
        var lease=store.claimModel(user,context,purpose,mutation);
        return compute(new OwnedGenerationStore.Candidate(user,context,purpose,mutation),lease,true,true);
    }
    public boolean readyForDispatch(){return enabled && availability.devJobsEnabled() && client.readyForMock();}
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
        return compute(task,lease,enqueue,false);
    }
    private JsonNode compute(OwnedGenerationStore.Candidate task,OwnedGenerationStore.Lease lease,boolean enqueue,boolean model){
        try{
            String owner=owners.ownedKey(task.user());
            if(enqueue){
                var queued=client.command("enqueue",owner,task.mutation(),lease.request());
                if(model&&ExplorationAiContract.CONVERSATION_VERSION.equals(lease.request().path("contractVersion").asText())&&queued.path("status").asText().equals("INSUFFICIENT_EVIDENCE"))return store.completeConversationInsufficient(task,lease.token());
                if(java.util.Set.of("INSUFFICIENT_EVIDENCE","BLOCKED","CANCELLED").contains(queued.path("status").asText()))throw new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.EVIDENCE_UNAVAILABLE);
            }
            var result=model?client.executeModel(owner,task.mutation(),lease.request()):client.executeMock(owner,task.mutation(),lease.request());
            return model?store.completeModel(task.user(),task.context(),task.purpose(),task.mutation(),lease.token(),result):store.completeMock(task.user(),task.context(),task.purpose(),task.mutation(),lease.token(),result);
        }catch(ExplorationJobClient.Unavailable e){store.failed(task,lease.token(),e.retryable(),e.failure().name());throw e;}
        catch(com.jinroon.jobe.domain.exploration.ai.ExplorationAiContract.Invalid e){store.failed(task,lease.token(),false,"OUTPUT_INVALID");throw e;}
        catch(org.springframework.dao.TransientDataAccessException | org.springframework.dao.DataAccessResourceFailureException | org.springframework.transaction.TransactionException e){store.failed(task,lease.token(),true,"TRANSIENT");throw e;}
        catch(com.jinroon.jobe.domain.exploration.api.ExplorationApiException e){store.failed(task,lease.token(),false,"SOURCE_UNAVAILABLE");throw e;}
        catch(RuntimeException e){store.failed(task,lease.token(),false,"UNEXPECTED_FAILURE");throw e;}
    }
}
