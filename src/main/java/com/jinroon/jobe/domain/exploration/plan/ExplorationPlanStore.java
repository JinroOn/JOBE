package com.jinroon.jobe.domain.exploration.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Frozen exploration plans. Reads never invoke a model, retry a job, or modify support answers. */
@Service
@RequiredArgsConstructor
public class ExplorationPlanStore {
    private final JdbcTemplate jdbc;
    private final ResultContextService contexts;
    private final PostResultSupportService support;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationAiContract contract;
    private final ExplorationJson json;
    private final com.jinroon.jobe.domain.exploration.activity.ActivityRecommendationService activityRecommendations;
    public record Draft(long id,long context,String mutation,JsonNode request) {}
    private record Locked(Map<String,Object> row,JsonNode snapshot) {}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private ExplorationApiException integrity(){return new ExplorationApiException(503,"PLAN_INTEGRITY_ERROR","계획 자료를 확인할 수 없습니다.");}
    private ExplorationApiException conflict(String code){return new ExplorationApiException(409,code,"계획 요청 상태를 확인한 뒤 다시 시도해 주세요.");}
    private void active(long user){var rows=jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",String.class,user);if(rows.size()!=1||!rows.get(0).equals("active"))throw conflict("PLAN_UNAVAILABLE");}
    private void validate(String key,String major,Long revision){if(key==null||!key.matches("[!-~]{1,128}")||major==null||!major.matches("[A-Za-z0-9_-]{1,100}")||(revision!=null&&revision<0))throw new ExplorationApiException(400,"INVALID_REQUEST","계획 요청 형식을 확인해 주세요.");}
    private ObjectNode clientBody(String major,Long revision){var b=json.object().put("majorCode",major);b.set("supportRevision",json.tree(revision));return b;}
    private Locked lock(long user,long id){
        active(user);
        var rows=jdbc.queryForList("SELECT p.* FROM exploration_plan p JOIN exploration_result r ON r.id=p.result_id JOIN exploration_session s ON s.id=r.session_id WHERE p.id=? AND s.user_id=?",id,user);
        if(rows.isEmpty())throw new ExplorationApiException(404,"RESOURCE_NOT_FOUND","계획을 찾을 수 없습니다.");
        var snapshot=contexts.lockForUse(user,number(rows.get(0),"context_id"),"PLAN").snapshot();
        var row=jdbc.queryForMap("SELECT * FROM exploration_plan WHERE id=? FOR UPDATE",id);checked(row,snapshot);return new Locked(row,snapshot);
    }
    private JsonNode checked(Map<String,Object> row,JsonNode snapshot){
        JsonNode request=json.read(row.get("request_json").toString()),client=json.read(row.get("client_request_json").toString());
        if(!Arrays.equals(json.hash(request),(byte[])row.get("request_hash"))||!Arrays.equals(json.hash(client),(byte[])row.get("client_request_hash")))throw integrity();
        try{contract.request(request);}catch(ExplorationAiContract.Invalid e){throw integrity();}
        if(!ExplorationAiContract.PLAN12_VERSION.equals(request.path("contractVersion").asText())||!row.get("generation_mutation").equals(request.path("requestId").asText())
            ||!request.equals(adapter.buildPlan(snapshot,request.path("requestId").asText()))
            ||!row.get("major_code").equals(snapshot.path("targetMajorCode").asText())
            ||!row.get("major_name").equals(snapshot.path("targetMajor").path("major_name").asText())
            ||number(row,"support_revision")!=snapshot.path("support").path("revision").asLong()
            ||number(row,"result_id")!=snapshot.path("source").path("resultId").asLong()
            ||!client.path("majorCode").asText().equals(row.get("major_code")))throw integrity();
        return request;
    }
    private Draft draft(Locked value){var row=value.row();return new Draft(number(row,"id"),number(row,"context_id"),row.get("generation_mutation").toString(),checked(row,value.snapshot()));}
    @Transactional public Draft find(long user,long result,String key,String major,Long revision){
        validate(key,major,revision);active(user);support.get(user,result);
        var rows=jdbc.queryForList("SELECT id,client_request_hash FROM exploration_plan WHERE result_id=? AND mutation_hash=?",result,json.hash(key));
        if(rows.isEmpty()){contexts.validatePlanRequest(user,result,revision,major);return null;}
        if(!Arrays.equals((byte[])rows.get(0).get("client_request_hash"),json.hash(clientBody(major,revision))))throw conflict("IDEMPOTENCY_CONFLICT");
        return draft(lock(user,number(rows.get(0),"id")));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Draft prepare(long user,long result,String key,String major,Long revision){
        var old=find(user,result,key,major,revision);if(old!=null)return old;
        var current=support.get(user,result);long captured=revision==null?current.path("supportRevision").asLong():revision;
        long context=contexts.prepare(user,result,"plan_context_"+UUID.randomUUID(),"PLAN",captured,major).body().path("contextId").asLong();
        JsonNode snapshot=contexts.lockForUse(user,context,"PLAN").snapshot();String mutation="plan_"+UUID.randomUUID();var request=adapter.buildPlan(snapshot,mutation);var client=clientBody(major,revision);
        jdbc.update("INSERT INTO exploration_plan(result_id,context_id,major_code,major_name,support_revision,mutation_hash,client_request_json,client_request_hash,generation_mutation,request_json,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",result,context,major,snapshot.path("targetMajor").path("major_name").asText(),captured,json.hash(key),client.toString(),json.hash(client),mutation,request.toString(),json.hash(request));
        long id=jdbc.queryForObject("SELECT id FROM exploration_plan WHERE generation_mutation=?",Long.class,mutation);return new Draft(id,context,mutation,request);
    }
    @Transactional public Draft owned(long user,long id){return draft(lock(user,id));}
    @Transactional(propagation=Propagation.MANDATORY)
    public void verifyRequest(long user,long context,JsonNode expected,boolean pending){
        var rows=jdbc.queryForList("SELECT id FROM exploration_plan WHERE context_id=? AND generation_mutation=?",context,expected.path("requestId").asText());
        if(rows.size()!=1)throw integrity();var value=lock(user,number(rows.get(0),"id"));
        if(!Arrays.equals(json.hash(expected),json.hash(checked(value.row(),value.snapshot()))))throw integrity();
        if(pending&&value.row().get("publication_json")!=null)throw conflict("PLAN_ALREADY_COMPLETED");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void protectCancellation(long context,String mutation){
        var rows=jdbc.queryForList("SELECT publication_json FROM exploration_plan WHERE context_id=? AND generation_mutation=? FOR UPDATE",context,mutation);
        if(!rows.isEmpty()&&rows.get(0).get("publication_json")!=null)throw conflict("PLAN_ALREADY_COMPLETED");
    }
    private Map<String,Object> generation(long user,Map<String,Object> plan){
        var rows=jdbc.queryForList("SELECT *, (lease_until>UTC_TIMESTAMP(6)) AS lease_live,(next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6)) AS retry_due,DATE_FORMAT(next_attempt_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS retry_at FROM exploration_generation_task WHERE user_id=? AND context_id=? AND mutation_key=? FOR UPDATE",user,plan.get("context_id"),plan.get("generation_mutation"));
        if(rows.size()!=1)throw integrity();var row=rows.get(0);
        if(!"MODEL".equals(row.get("execution_mode"))||!"PLAN".equals(row.get("purpose"))||!Arrays.equals(json.hash(json.read(row.get("request_json").toString())),(byte[])plan.get("request_hash")))throw integrity();return row;
    }
    private boolean truth(Object value){return value instanceof Boolean b?b:value instanceof Number n&&n.intValue()==1;}
    @Transactional public boolean canExecute(long user,long id){
        var value=lock(user,id);if(value.row().get("publication_json")!=null)return false;var g=generation(user,value.row());
        return number(g,"attempt")<3&&truth(g.get("retry_due"))&&(g.get("state").equals("QUEUED")||g.get("state").equals("RUNNING")&&!truth(g.get("lease_live")));
    }
    @Transactional public JsonNode get(long user,long id){return view(user,lock(user,id),true);}
    @Transactional public JsonNode list(long user,long result){
        active(user);support.get(user,result);var out=json.array();
        for(Long id:jdbc.queryForList("SELECT id FROM exploration_plan WHERE result_id=? ORDER BY id DESC",Long.class,result)){
            var locked=lock(user,id);
            try{out.add(view(user,locked,false));}
            catch(ExplorationApiException e){if(!e.getCode().equals("PLAN_INTEGRITY_ERROR"))throw e;
                var item=summary(locked.row()).put("status","FAILED").put("failureCode","PLAN_INTEGRITY_ERROR").put("retryable",false);item.putNull("nextRetryAt");out.add(item);}
        }return out;
    }
    private JsonNode view(long user,Locked value,boolean detail){
        var row=value.row();var g=generation(user,row);String state=g.get("state").toString(),status;boolean retryable=false;String failure=null;JsonNode data=null;
        if(row.get("publication_json")!=null){
            data=json.read(row.get("publication_json").toString());var proof=json.read(row.get("proof_json").toString());
            if(!Arrays.equals(json.hash(data),(byte[])row.get("publication_hash"))||!Arrays.equals(json.hash(proof),(byte[])row.get("proof_hash"))
                ||!state.equals("MODEL_STORED")||!json.hex(proof).equals(g.get("result_hash"))||!Arrays.equals(json.hash(proof),json.hash(json.read(g.get("result_json").toString()))))throw integrity();
            if(!Arrays.equals(json.hash(data),json.hash(project(checked(row,value.snapshot()),proof,value.snapshot()))))throw integrity();
            status=data.path("status").asText();if(status.equals("FAILED"))failure=proof.path("response").path("status").asText().equals("INSUFFICIENT_EVIDENCE")?"EVIDENCE_UNAVAILABLE":"MODEL_DECLINED";
        }else if(state.equals("QUEUED")||state.equals("RUNNING")){
            boolean active=state.equals("RUNNING")&&truth(g.get("lease_live"));
            status=active||state.equals("QUEUED")&&number(g,"attempt")==0?"GENERATING":"FAILED";
            retryable=!active&&number(g,"attempt")<3;
            failure=status.equals("FAILED")?(state.equals("RUNNING")?number(g,"attempt")>=3?"ATTEMPTS_EXHAUSTED":"LEASE_EXPIRED":failureCode(g)):null;
        }else if(state.equals("FAILED")||state.equals("CANCELLED")){status="FAILED";failure=state.equals("CANCELLED")?"CANCELLED":failureCode(g);}
        else throw integrity();
        var out=summary(row).put("status",status);
        out.put("retryable",retryable);out.set("failureCode",json.tree(failure));out.set("nextRetryAt",json.tree(retryable?g.get("retry_at"):null));
        if(detail){out.put("supportRevision",number(row,"support_revision"));out.set("weeks",data==null?json.array():data.path("weeks"));out.set("sources",data==null?json.array():data.path("sources"));}
        return out;
    }
    private ObjectNode summary(Map<String,Object> row){
        return json.object().put("planId",number(row,"id")).put("resultId",number(row,"result_id")).put("majorCode",row.get("major_code").toString()).put("majorName",row.get("major_name").toString())
            .put("createdAt",jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') FROM exploration_plan WHERE id=?",String.class,row.get("id")));
    }
    private String failureCode(Map<String,Object> g){String value=(String)g.get("failure_code");if(value==null||!Set.of("TRANSIENT","CONFIGURATION","AUTHENTICATION","INVALID_RESPONSE","INVALID_REQUEST","CONFLICT","EVIDENCE_UNAVAILABLE","INTEGRITY","SOURCE_UNAVAILABLE","OUTPUT_INVALID","UNEXPECTED_FAILURE","ATTEMPTS_EXHAUSTED").contains(value))throw integrity();return value;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(long user,long context,JsonNode original,JsonNode proof){
        verifyRequest(user,context,original,true);var row=jdbc.queryForMap("SELECT * FROM exploration_plan WHERE generation_mutation=? FOR UPDATE",original.path("requestId").asText());var g=generation(user,row);
        if(!g.get("state").equals("MODEL_STORED")||!json.hex(proof).equals(g.get("result_hash")))throw integrity();
        var snapshot=lock(user,number(row,"id")).snapshot();
        var basis=snapshot.path("planActivityBasis");
        if(!basis.path("activities").isEmpty()&&!activityRecommendations.planBasisApproved(basis,row.get("major_code").toString())) {
            throw new ExplorationApiException(409,"PLAN_ACTIVITY_SOURCE_UNAVAILABLE","계획에 쓰려던 활동의 검토 상태가 바뀌었어요. 새 계획을 요청해 주세요.");
        }
        var data=project(original,proof,snapshot);
        jdbc.update("UPDATE exploration_plan SET publication_json=?,publication_hash=?,proof_json=?,proof_hash=? WHERE id=? AND publication_json IS NULL",data.toString(),json.hash(data),proof.toString(),json.hash(proof),row.get("id"));
    }
    private JsonNode project(JsonNode original,JsonNode proof,JsonNode snapshot){
        JsonNode bound=proof.path("boundRequest"),response=proof.path("response");
        try{contract.response(bound,response);}catch(ExplorationAiContract.Invalid e){throw integrity();}
        for(String field:List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!original.path(field).equals(bound.path(field)))throw integrity();
        if(!"MODEL".equals(proof.path("mode").asText())||!proof.path("generationAuthorized").asBoolean()||!original.path("inputHash").equals(proof.path("originalInputHash")))throw integrity();
        var out=json.object().put("status",response.path("status").asText().equals("SUCCEEDED")?"READY":"FAILED");var weeks=out.putArray("weeks");var sources=out.putArray("sources");
        if(out.path("status").asText().equals("READY")){
            Set<String> used=new LinkedHashSet<>();for(JsonNode w:response.path("result").path("weeks")){var publicWeek=weeks.addObject();for(String f:List.of("week","phase","title","tasks","expectedOutput"))publicWeek.set(f,w.path(f));w.path("referenceKeys").forEach(r->used.add(r.asText()));}
            for(String ref:used){boolean found=false;for(JsonNode r:bound.path("evidence").path("referenceKeys"))if(ref.equals(r.asText()))found=true;if(!found)throw integrity();
                sources.addObject().put("kind","MAJOR_PROFILE").put("title",bound.path("input").path("targetName").asText()+" 승인 전공 자료").put("version",bound.path("evidence").path("datasetVersion").asText());}
            var basis=snapshot.path("planActivityBasis");
            if(PlanActivitySchedule.apply(weeks,basis))sources.addObject().put("kind","ACTIVITY_CATALOG")
                    .put("title",bound.path("input").path("targetName").asText()+" 승인 탐색 활동")
                    .put("version",basis.path("catalogVersion").asText());
        }
        return out;
    }
}
