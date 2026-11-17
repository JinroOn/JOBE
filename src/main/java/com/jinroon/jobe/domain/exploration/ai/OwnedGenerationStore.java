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
    public record Lease(String token,JsonNode request) {}
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
        var actual=adapter.build(contexts.lockForUse(user,context,purpose).snapshot(),expected.path("requestId").asText());
        if(!actual.equals(expected))throw conflict();
    }
    @Transactional public void reserve(long user,long context,String purpose,String mutation,JsonNode request){
        active(user);current(user,context,purpose,request);
        jdbc.update("INSERT IGNORE INTO exploration_generation_task(user_id,context_id,purpose,mutation_key,request_json,state) VALUES (?,?,?,?,?,'QUEUED')",user,context,purpose,mutation,request.toString());
        var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")) || !json.read(row.get("request_json").toString()).equals(request))throw conflict();
    }
    private Map<String,Object> locked(long user,String mutation){
        var rows=jdbc.queryForList("SELECT *, (lease_until>UTC_TIMESTAMP(6)) AS lease_live FROM exploration_generation_task WHERE user_id=? AND mutation_key=? FOR UPDATE",user,mutation);
        if(rows.isEmpty())throw conflict();return rows.get(0);
    }
    private boolean live(Map<String,Object> row){Object value=row.get("lease_live");return value instanceof Boolean b?b:value instanceof Number n&&n.intValue()==1;}
    @Transactional public Lease claim(long user,long context,String purpose,String mutation){
        active(user);
        // All mutations take user -> source context -> task locks in the same order.
        contexts.lockForUse(user,context,purpose);
        var row=locked(user,mutation);JsonNode request=json.read(row.get("request_json").toString());
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")))throw conflict();
        current(user,context,purpose,request);
        String state=row.get("state").toString();int attempt=((Number)row.get("attempt")).intValue();
        if(!(state.equals("QUEUED") || state.equals("RUNNING")&&!live(row)) || attempt>=3)throw conflict();
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE exploration_generation_task SET state='RUNNING',attempt=attempt+1,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND) WHERE id=?",token,row.get("id"));
        return new Lease(token,request.deepCopy());
    }
    @Transactional public void cancel(long user,long context,String purpose,String mutation){
        active(user);contexts.lockForUse(user,context,purpose);var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")))throw conflict();
        jdbc.update("UPDATE exploration_generation_task SET state='CANCELLED',lease_token=NULL,lease_until=NULL,result_json=NULL,result_hash=NULL WHERE id=?",row.get("id"));
    }
    @Transactional public JsonNode completeMock(long user,long context,String purpose,String mutation,String lease,JsonNode envelope){
        active(user);contexts.lockForUse(user,context,purpose);var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")) || !row.get("state").equals("RUNNING") || !lease.equals(row.get("lease_token")) || !live(row))throw conflict();
        JsonNode original=json.read(row.get("request_json").toString());current(user,context,purpose,original);
        if(envelope.size()!=10 || !envelope.path("transportVersion").asText().equals("EXPLORATION_MOCK_EXECUTION_V1_0") || !envelope.path("sourceContextHash").equals(original.path("sourceContextHash")))throw conflict();
        var bound=envelope.path("boundRequest");contract.request(bound);contract.response(bound,envelope.path("response"));
        for(String key:List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!original.path(key).equals(bound.path(key)))throw conflict();
        if(!envelope.path("mode").asText().equals("MOCK") || !envelope.path("generationAuthorized").isBoolean() || envelope.path("generationAuthorized").asBoolean() || !bound.path("evidence").path("bindingStatus").asText().equals("BOUND") || !envelope.path("mutationKey").asText().equals(mutation) || !envelope.path("originalInputHash").equals(original.path("inputHash")) || !envelope.path("bindingHash").asText().matches("[a-f0-9]{64}") || !envelope.path("jobId").isIntegralNumber() || envelope.path("jobId").asLong()<=0)throw conflict();
        String body=envelope.toString();String hash=json.hex(envelope);
        int saved=jdbc.update("UPDATE exploration_generation_task SET state='MOCK_STORED',result_json=?,result_hash=?,lease_token=NULL,lease_until=NULL WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",body,hash,row.get("id"),lease);
        if(saved!=1)throw conflict();
        var reply=json.object().put("status","MOCK_STORED").put("mode","MOCK").put("generationAuthorized",false);reply.set("response",envelope.path("response").deepCopy());return reply;
    }
    @Transactional public JsonNode completed(long user,long context,String purpose,String mutation){
        active(user);contexts.lockForUse(user,context,purpose);var row=locked(user,mutation);
        if(((Number)row.get("context_id")).longValue()!=context || !purpose.equals(row.get("purpose")))throw conflict();
        current(user,context,purpose,json.read(row.get("request_json").toString()));
        if(!row.get("state").equals("MOCK_STORED"))return null;
        JsonNode result=json.read(row.get("result_json").toString());if(!json.hex(result).equals(row.get("result_hash")))throw conflict();
        contract.response(result.path("boundRequest"),result.path("response"));
        var reply=json.object().put("status","MOCK_STORED").put("mode","MOCK").put("generationAuthorized",false);reply.set("response",result.path("response").deepCopy());return reply;
    }
    private static ExplorationApiException conflict(){return new ExplorationApiException(409,"GENERATION_TASK_CONFLICT","Generation task is unavailable or changed");}
}
