package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.jinroon.jobe.domain.exploration.entity.ExplorationResult;
import com.jinroon.jobe.domain.exploration.entity.ExplorationSession;

/** MySQL is authoritative for account/context/cancellation and user result persistence. */
@Service
@RequiredArgsConstructor
public class OwnedGenerationStore {
    private final ResultContextService contexts;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationAiContract contract;
    private final ExplorationJson json;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final com.jinroon.jobe.domain.exploration.consultation.ConsultationTurnStore conversations;
    private final com.jinroon.jobe.domain.exploration.plan.ExplorationPlanStore plans;
    public record Lease(String token,JsonNode request) {}
    public record Candidate(long user,long context,String purpose,String mutation) {}
    private void active(long user){
        // A locking SQL read also avoids reusing a pre-call JPA first-level cached status.
        var rows=jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",String.class,user);
        if(rows.isEmpty() || !rows.get(0).equals("active"))throw conflict();
    }
    private void current(long user,long context,String purpose,JsonNode expected){
        contract.request(expected);
        var source=contexts.lockForUse(user,context,purpose).snapshot().path("source");
        // OpenEntityManagerInView may retain pre-network entities. Explicit locking refresh
        // uses current DB rows instead of treating the persistence-context cache as authority.
        entities.refresh(entities.getReference(ExplorationSession.class,source.path("sessionId").asLong()),LockModeType.PESSIMISTIC_WRITE);
        entities.refresh(entities.getReference(ExplorationResult.class,source.path("resultId").asLong()),LockModeType.PESSIMISTIC_WRITE);
        if(ExplorationAiContract.CONVERSATION_VERSION.equals(expected.path("contractVersion").asText())){
            conversations.verifyRequest(user,context,expected,false);return;
        }
        if(isPlan(expected)){plans.verifyRequest(user,context,expected,false);return;}
        var actual=adapter.build(contexts.lockForUse(user,context,purpose).snapshot(),expected.path("requestId").asText());
        if(!actual.equals(expected))throw conflict();
    }
    @Transactional public void reserve(long user,long context,String purpose,String mutation,JsonNode request){reserveMode(user,context,purpose,mutation,request,"MOCK");}
    @Transactional public void reserveModel(long user,long context,String purpose,String mutation,JsonNode request){reserveMode(user,context,purpose,mutation,request,"MODEL");}
    @Transactional public void reserveForCancel(long user,long context,String purpose,String mutation,JsonNode request){reserveMode(user,context,purpose,mutation,request,null);}
    private void reserveMode(long user,long context,String purpose,String mutation,JsonNode request,String mode){
        active(user);current(user,context,purpose,request);
        if((isConversation(request)||isPlan(request))&&mode!=null&&!mode.equals("MODEL"))throw conflict();
        jdbc.update("INSERT IGNORE INTO exploration_generation_task(user_id,context_id,purpose,mutation_key,request_json,state,next_attempt_at,execution_mode) VALUES (?,?,?,?,?,'QUEUED',UTC_TIMESTAMP(6),?)",user,context,purpose,mutation,request.toString(),mode==null?"MOCK":mode);
        var row=locked(user,mutation);
        if(mode!=null && !mode.equals(row.get("execution_mode")))throw conflict();
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")) || !json.read(row.get("request_json").toString()).equals(request))throw conflict();
    }
    private Map<String,Object> locked(long user,String mutation){
        var rows=jdbc.queryForList("SELECT *, (lease_until>UTC_TIMESTAMP(6)) AS lease_live, (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6)) AS retry_due FROM exploration_generation_task WHERE user_id=? AND mutation_key=? FOR UPDATE",user,mutation);
        if(rows.isEmpty())throw conflict();return rows.get(0);
    }
    private boolean live(Map<String,Object> row){Object value=row.get("lease_live");return value instanceof Boolean b?b:value instanceof Number n&&n.intValue()==1;}
    @Transactional public Lease claim(long user,long context,String purpose,String mutation){return claimMode(user,context,purpose,mutation,false);}
    @Transactional public Lease claimModel(long user,long context,String purpose,String mutation){return claimMode(user,context,purpose,mutation,true);}
    private Lease claimMode(long user,long context,String purpose,String mutation,boolean model){
        active(user);
        // All mutations take user -> source context -> task locks in the same order.
        contexts.lockForUse(user,context,purpose);
        var row=locked(user,mutation);JsonNode request=json.read(row.get("request_json").toString());
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")))throw conflict();
        current(user,context,purpose,request);
        if(isConversation(request)){if(!model)throw conflict();conversations.verifyRequest(user,context,request,true);}
        if(isPlan(request)){if(!model)throw conflict();plans.verifyRequest(user,context,request,true);}
        String state=row.get("state").toString();int attempt=((Number)row.get("attempt")).intValue();
        if(!(state.equals("QUEUED") || state.equals("RUNNING")&&!live(row)) || attempt>=3 || !truth(row.get("retry_due")))throw conflict();
        String mode=model?"MODEL":"MOCK";
        if(!mode.equals(row.get("execution_mode")))throw conflict();
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE exploration_generation_task SET state='RUNNING',attempt=attempt+1,execution_mode=?,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND),next_attempt_at=NULL,failure_code=NULL WHERE id=?",mode,token,row.get("id"));
        return new Lease(token,request.deepCopy());
    }
    @Transactional public void cancel(long user,long context,String purpose,String mutation){
        active(user);contexts.lockForUse(user,context,purpose);var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")))throw conflict();
        conversations.cancelPending(context,mutation);
        plans.protectCancellation(context,mutation);
        jdbc.update("UPDATE exploration_generation_task SET state='CANCELLED',lease_token=NULL,lease_until=NULL,result_json=NULL,result_hash=NULL WHERE id=?",row.get("id"));
    }
    @Transactional public JsonNode completeMock(long user,long context,String purpose,String mutation,String lease,JsonNode envelope){return completeMode(user,context,purpose,mutation,lease,envelope,false);}
    @Transactional public JsonNode completeModel(long user,long context,String purpose,String mutation,String lease,JsonNode envelope){return completeMode(user,context,purpose,mutation,lease,envelope,true);}
    private JsonNode completeMode(long user,long context,String purpose,String mutation,String lease,JsonNode envelope,boolean model){
        String mode=model?"MODEL":"MOCK";String state=model?"MODEL_STORED":"MOCK_STORED";
        active(user);contexts.lockForUse(user,context,purpose);var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")) || !row.get("state").equals("RUNNING") || !lease.equals(row.get("lease_token")) || !live(row))throw conflict();
        JsonNode original=json.read(row.get("request_json").toString());current(user,context,purpose,original);
        if(isConversation(original)){if(!model)throw conflict();conversations.verifyRequest(user,context,original,true);}
        if(isPlan(original)){if(!model)throw conflict();plans.verifyRequest(user,context,original,true);}
        if(!mode.equals(row.get("execution_mode")) || envelope.size()!=(model?11:10) || !envelope.path("transportVersion").asText().equals(model?"EXPLORATION_MODEL_EXECUTION_V1_0":"EXPLORATION_MOCK_EXECUTION_V1_0") || !envelope.path("sourceContextHash").equals(original.path("sourceContextHash")))throw conflict();
        var bound=envelope.path("boundRequest");contract.request(bound);contract.response(bound,envelope.path("response"));
        for(String key:List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!original.path(key).equals(bound.path(key)))throw conflict();
        if(!envelope.path("mode").asText().equals(mode) || !envelope.path("generationAuthorized").isBoolean() || envelope.path("generationAuthorized").asBoolean()!=model || !bound.path("evidence").path("bindingStatus").asText().equals("BOUND") || !envelope.path("mutationKey").asText().equals(mutation) || !envelope.path("originalInputHash").equals(original.path("inputHash")) || !envelope.path("bindingHash").asText().matches("[a-f0-9]{64}") || !envelope.path("jobId").isIntegralNumber() || envelope.path("jobId").asLong()<=0)throw conflict();
        if(model){String prompt=ExplorationAiContract.promptVersion(original);var metadata=envelope.path("providerMetadata");if(!metadata.isObject() || metadata.size()!=3 || !Set.of("openai","openai_compatible","factchat").contains(metadata.path("provider").asText()) || metadata.path("model").asText().isBlank() || metadata.path("model").asText().length()>80 || !prompt.equals(metadata.path("promptVersion").asText()))throw conflict();}
        String body=envelope.toString();String hash=json.hex(envelope);
        int saved=jdbc.update("UPDATE exploration_generation_task SET state=?,result_json=?,result_hash=?,lease_token=NULL,lease_until=NULL WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",state,body,hash,row.get("id"),lease);
        if(saved!=1)throw conflict();
        if(isConversation(original))conversations.publish(user,context,original,envelope);
        if(isPlan(original))plans.publish(user,context,original,envelope);
        var reply=json.object().put("status",state).put("mode",mode).put("generationAuthorized",model);reply.set("response",envelope.path("response").deepCopy());return reply;
    }
    @Transactional public JsonNode completed(long user,long context,String purpose,String mutation){return completedMode(user,context,purpose,mutation,false);}
    @Transactional public JsonNode completedModel(long user,long context,String purpose,String mutation){return completedMode(user,context,purpose,mutation,true);}
    private JsonNode completedMode(long user,long context,String purpose,String mutation,boolean model){
        String mode=model?"MODEL":"MOCK";String state=model?"MODEL_STORED":"MOCK_STORED";
        active(user);contexts.lockForUse(user,context,purpose);var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")))throw conflict();
        current(user,context,purpose,json.read(row.get("request_json").toString()));
        if(((Number)row.get("attempt")).intValue()>0 && !mode.equals(row.get("execution_mode")))throw conflict();
        if(!row.get("state").equals(state))return null;
        JsonNode result=json.read(row.get("result_json").toString());if(!json.hex(result).equals(row.get("result_hash")))throw conflict();
        contract.response(result.path("boundRequest"),result.path("response"));
        var reply=json.object().put("status",state).put("mode",mode).put("generationAuthorized",model);reply.set("response",result.path("response").deepCopy());return reply;
    }
    private static ExplorationApiException conflict(){return new ExplorationApiException(409,"GENERATION_TASK_CONFLICT","Generation task is unavailable or changed");}
    private boolean isConversation(JsonNode request){return ExplorationAiContract.CONVERSATION_VERSION.equals(request.path("contractVersion").asText());}
    private boolean isPlan(JsonNode request){return ExplorationAiContract.PLAN12_VERSION.equals(request.path("contractVersion").asText());}
    @Transactional public JsonNode requestForOperation(long user,long context,String purpose,String mutation){
        active(user);contexts.lockForUse(user,context,purpose);
        var rows=jdbc.queryForList("SELECT request_json,purpose,context_id FROM exploration_generation_task WHERE user_id=? AND mutation_key=?",user,mutation);
        if(rows.isEmpty())return null;var row=rows.get(0);
        if(number(row,"context_id")!=context||!purpose.equals(row.get("purpose")))throw conflict();
        JsonNode r=json.read(row.get("request_json").toString());current(user,context,purpose,r);return r;
    }
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    @Transactional public JsonNode completeConversationInsufficient(Candidate task,String lease){
        active(task.user());contexts.lockForUse(task.user(),task.context(),task.purpose());var row=locked(task.user(),task.mutation());
        if(number(row,"context_id")!=task.context()||!row.get("purpose").equals("CONSULTATION")||!row.get("execution_mode").equals("MODEL")
            ||!row.get("state").equals("RUNNING")||!lease.equals(row.get("lease_token"))||!live(row))throw conflict();
        JsonNode original=json.read(row.get("request_json").toString());current(task.user(),task.context(),task.purpose(),original);
        if(!isConversation(original))throw conflict();
        if(jdbc.update("UPDATE exploration_generation_task SET state='FAILED',failure_code='EVIDENCE_UNAVAILABLE',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",row.get("id"),lease)!=1)throw conflict();
        var proof=json.object().put("kind","NO_APPROVED_EVIDENCE");proof.set("inputHash",original.path("inputHash"));
        conversations.publish(task.user(),task.context(),original,proof);
        return json.object().put("status","INSUFFICIENT_EVIDENCE").put("generationAuthorized",false);
    }
    private boolean truth(Object value){return value instanceof Boolean b?b:value instanceof Number n&&n.intValue()==1;}
    @Transactional(readOnly=true) public List<Candidate> candidates(int limit){
        if(limit<1 || limit>25)throw conflict();
        return jdbc.query("SELECT user_id,context_id,purpose,mutation_key FROM exploration_generation_task WHERE execution_mode='MOCK' AND attempt<3 AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6)) AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6))) ORDER BY COALESCE(next_attempt_at,'1000-01-01'),id LIMIT ?",(rs,n)->new Candidate(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4)),limit);
    }
    @Transactional(readOnly = true)
    public List<Candidate> explanationCandidates() {
        return jdbc.query("""
                SELECT user_id,context_id,purpose,mutation_key FROM exploration_generation_task
                WHERE execution_mode='MODEL' AND purpose='EXPLANATION' AND attempt<3
                  AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6))
                  AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)))
                ORDER BY COALESCE(next_attempt_at,'1000-01-01'),id LIMIT 5
                """, (rs, n) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
    }
    @Transactional public int exhaustExpired(){
        return jdbc.update("UPDATE exploration_generation_task SET state='FAILED',failure_code='ATTEMPTS_EXHAUSTED',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE state='RUNNING' AND attempt>=3 AND lease_until<=UTC_TIMESTAMP(6) LIMIT 25");
    }
    @Transactional public void failed(Candidate task,String lease,boolean retryable,String code){
        if(!Set.of("TRANSIENT","CONFIGURATION","AUTHENTICATION","INVALID_RESPONSE","INVALID_REQUEST","CONFLICT","EVIDENCE_UNAVAILABLE","INTEGRITY","SOURCE_UNAVAILABLE","OUTPUT_INVALID","UNEXPECTED_FAILURE").contains(code))throw conflict();
        // No result writes. Token condition cannot overwrite cancellation, deletion or a newer claim.
        var rows=jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",String.class,task.user());
        if(rows.isEmpty())return;
        var jobs=jdbc.queryForList("SELECT id,attempt FROM exploration_generation_task WHERE user_id=? AND context_id=? AND mutation_key=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",task.user(),task.context(),task.mutation(),lease);
        if(jobs.isEmpty())return;
        int attempt=((Number)jobs.get(0).get("attempt")).intValue();boolean retry=retryable && attempt<3 && rows.get(0).equals("active");
        int delay=attempt<=1?15:30;
        jdbc.update("UPDATE exploration_generation_task SET state=?,failure_code=?,lease_token=NULL,lease_until=NULL,next_attempt_at=IF(?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),NULL) WHERE id=?",retry?"QUEUED":"FAILED",retryable&&attempt>=3?"ATTEMPTS_EXHAUSTED":code,retry,delay,jobs.get(0).get("id"));
    }
    @Transactional public void rejectUnclaimed(Candidate task){
        jdbc.update("UPDATE exploration_generation_task SET state='FAILED',failure_code='SOURCE_UNAVAILABLE',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE user_id=? AND context_id=? AND mutation_key=? AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6)) AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)))",task.user(),task.context(),task.mutation());
    }
    @Transactional public JsonNode status(long user,long context,String purpose,String mutation,JsonNode expected){
        active(user);current(user,context,purpose,expected);
        var rows=jdbc.queryForList("SELECT *,DATE_FORMAT(next_attempt_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS next_retry_at FROM exploration_generation_task WHERE user_id=? AND mutation_key=? FOR UPDATE",user,mutation);
        if(rows.isEmpty())return null;var row=rows.get(0);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")) || !json.read(row.get("request_json").toString()).equals(expected))throw conflict();
        var out=json.object().put("statusVersion",row.get("execution_mode").equals("MODEL")?"OWNED_MODEL_TASK_STATUS_V1_0":"OWNED_MOCK_TASK_STATUS_V1_0").put("status",row.get("state").toString()).put("mode",row.get("execution_mode").toString()).put("generationAuthorized",row.get("state").equals("MODEL_STORED")).put("mutationKey",mutation).put("attempt",((Number)row.get("attempt")).intValue());
        out.set("nextRetryAt",json.tree(row.get("next_retry_at")));out.set("failureCode",json.tree(row.get("failure_code")));
        return out;
    }
}
