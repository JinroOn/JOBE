package com.jinroon.jobe.domain.exploration.consultation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** All dialogue comes from owned completed pairs. This class never invokes a remote service. */
@Service
@RequiredArgsConstructor
public class ConsultationTurnStore {
    private final JdbcTemplate jdbc;
    private final ResultContextService contexts;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationAiContract contract;
    private final ExplorationJson json;
    public record Draft(long id,long consultation,long context,String mutation,JsonNode request) {}
    private record Locked(Map<String,Object> session,JsonNode snapshot) {}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private ExplorationApiException integrity(){return new ExplorationApiException(503,"CONSULTATION_INTEGRITY_ERROR","상담 기록을 확인할 수 없습니다.");}
    private ExplorationApiException conflict(String code){return new ExplorationApiException(409,code,"상담 요청 상태를 확인한 뒤 다시 시도해 주세요.");}
    private void active(long user){
        var statuses=jdbc.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",String.class,user);
        if(statuses.size()!=1||!statuses.get(0).equals("active"))throw conflict("CONSULTATION_UNAVAILABLE");
    }
    private Locked lock(long user,long id){
        active(user);
        var found=jdbc.queryForList("SELECT c.* FROM exploration_consultation c JOIN exploration_result r ON r.id=c.result_id JOIN exploration_session s ON s.id=r.session_id WHERE c.id=? AND s.user_id=?",id,user);
        if(found.isEmpty())throw new ExplorationApiException(404,"RESOURCE_NOT_FOUND","상담을 찾을 수 없습니다.");
        long context=number(found.get(0),"context_id");
        var snapshot=contexts.lockForUse(user,context,"CONSULTATION").snapshot();
        return new Locked(jdbc.queryForMap("SELECT * FROM exploration_consultation WHERE id=? FOR UPDATE",id),snapshot);
    }
    private void validate(String key,String question){
        if(key==null||!key.matches("[!-~]{1,128}"))throw new ExplorationApiException(400,"INVALID_REQUEST","멱등 키 형식을 확인해 주세요.");
        var value=json.object();value.put("question",question);value.putArray("history");
        try{contract.conversation(value);}catch(ExplorationAiContract.Invalid e){throw new ExplorationApiException(400,"INVALID_REQUEST","질문은 공백이 아닌 2000자 이내 문자열이어야 합니다.");}
    }
    private List<Map<String,Object>> turns(long consultation){return jdbc.queryForList("SELECT * FROM exploration_consultation_turn WHERE consultation_id=? ORDER BY turn_no",consultation);}
    private JsonNode request(Map<String,Object> row){
        JsonNode r=json.read(row.get("request_json").toString());
        if(!Arrays.equals(json.hash(r),(byte[])row.get("request_hash")))throw integrity();
        try{contract.request(r);}catch(ExplorationAiContract.Invalid e){throw integrity();}
        if(!ExplorationAiContract.CONVERSATION_VERSION.equals(r.path("contractVersion").asText())||!row.get("generation_mutation").equals(r.path("requestId").asText()))throw integrity();
        return r;
    }
    private ObjectNode conversation(List<JsonNode> messages,String question){
        // Keep complete pairs only; dropping old pairs does not turn them into approved facts.
        int from=Math.max(0,messages.size()-6),size=question.codePointCount(0,question.length());
        for(int i=from;i<messages.size();i++){String text=messages.get(i).path("content").asText();size+=text.codePointCount(0,text.length());}
        while(size>8000&&from<messages.size())for(int n=0;n<2;n++){String text=messages.get(from++).path("content").asText();size-=text.codePointCount(0,text.length());}
        var result=json.object().put("question",question);var history=result.putArray("history");
        for(int i=from;i<messages.size();i++){var m=history.addObject();m.set("role",messages.get(i).path("role"));m.set("content",messages.get(i).path("content"));}
        contract.conversation(result);return result;
    }
    private void rebuilt(Map<String,Object> turn,JsonNode snapshot,List<JsonNode> prior){
        JsonNode r=request(turn);String question=r.path("input").path("conversation").path("question").asText();
        boolean legacyTarget=r.path("input").path("targetName").isTextual();
        if(!r.equals(adapter.buildConversation(snapshot,r.path("requestId").asText(),conversation(prior,question),legacyTarget)))throw integrity();
    }
    private List<JsonNode> verified(Locked locked,Long before){
        var messages=new ArrayList<JsonNode>();long completed=0;
        for(var t:turns(number(locked.session(),"id"))){
            if(before!=null&&number(t,"turn_no")>=before)break;
            rebuilt(t,locked.snapshot(),messages);
            if(!t.get("state").equals("COMPLETED")){
                if(jdbc.queryForObject("SELECT COUNT(*) FROM exploration_consultation_message WHERE turn_id=?",Integer.class,t.get("id"))!=0)throw integrity();
                continue;
            }
            if(number(t,"base_revision")!=completed)throw integrity();
            JsonNode reply=json.read(t.get("reply_json").toString()),proof=json.read(t.get("proof_json").toString());
            if(!Arrays.equals(json.hash(reply),(byte[])t.get("reply_hash"))||!Arrays.equals(json.hash(proof),(byte[])t.get("proof_hash")))throw integrity();
            var projection=projection(request(t),proof);
            var rows=jdbc.queryForList("SELECT *,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS created FROM exploration_consultation_message WHERE turn_id=? ORDER BY FIELD(role,'user','assistant')",t.get("id"));
            if(rows.size()!=2||reply.size()!=2)throw integrity();
            for(int i=0;i<2;i++){
                String role=i==0?"user":"assistant";var expected=projection.get(i).deepCopy();
                expected.put("id",number(rows.get(i),"id")).put("createdAt",rows.get(i).get("created").toString());
                var row=rows.get(i);
                if(!role.equals(row.get("role"))||!expected.path("content").asText().equals(row.get("content"))
                    ||!expected.path("sources").equals(json.read(row.get("sources_json").toString()))
                    ||!Objects.equals(expected.path("evidenceStatus").textValue(),row.get("evidence_status"))
                    ||!Arrays.equals(json.hash(expected),(byte[])row.get("message_hash"))
                    ||!Arrays.equals(json.hash(expected),json.hash(reply.path(i==0?"userMessage":"assistantMessage"))))throw integrity();
                messages.add(expected);
            }
            completed++;
        }
        if(before==null&&completed!=number(locked.session(),"revision"))throw integrity();
        return messages;
    }
    @Transactional public List<JsonNode> history(long user,long consultation){var locked=lock(user,consultation);releaseTerminal(consultation);return verified(locked,null);}
    @Transactional public ConsultationDtos.Pending pending(long user,long consultation){
        var locked=lock(user,consultation);releaseTerminal(consultation);verified(locked,null);
        var rows=jdbc.queryForList("SELECT t.*,g.state AS generation_state,DATE_FORMAT(g.next_attempt_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS retry_at FROM exploration_consultation_turn t JOIN exploration_generation_task g ON g.context_id=? AND g.mutation_key=t.generation_mutation WHERE t.consultation_id=? AND t.state='PENDING'",locked.session().get("context_id"),consultation);
        if(rows.isEmpty()){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM exploration_consultation_turn WHERE consultation_id=? AND state='PENDING'",Integer.class,consultation)!=0)throw integrity();
            return null;
        }
        if(rows.size()!=1)throw integrity();var row=rows.get(0);
        return new ConsultationDtos.Pending(row.get("generation_mutation").toString(),request(row).path("input").path("conversation").path("question").asText(),row.get("generation_state").toString(),(String)row.get("retry_at"));
    }
    private List<Map<String,Object>> byKey(long consultation,String key){
        var rows=jdbc.queryForList("SELECT * FROM exploration_consultation_turn WHERE consultation_id=? AND (mutation_hash=? OR generation_mutation=?) FOR UPDATE",consultation,json.hash(key),key);
        if(rows.size()>1)throw conflict("IDEMPOTENCY_CONFLICT");return rows;
    }
    @Transactional public Draft cancellation(long user,long consultation,String retryKey){
        if(retryKey==null||!retryKey.matches("chat_[a-f0-9-]{36}"))throw new ExplorationApiException(400,"INVALID_REQUEST","상담 재시도 키를 확인해 주세요.");
        var locked=lock(user,consultation);verified(locked,null);
        var rows=jdbc.queryForList("SELECT * FROM exploration_consultation_turn WHERE consultation_id=? AND generation_mutation=? FOR UPDATE",consultation,retryKey);
        if(rows.isEmpty())throw new ExplorationApiException(404,"RESOURCE_NOT_FOUND","상담 요청을 찾을 수 없습니다.");var row=rows.get(0);
        if(row.get("state").equals("COMPLETED"))throw conflict("CONSULTATION_ALREADY_COMPLETED");
        if(row.get("state").equals("FAILED")&&!"CANCELLED".equals(row.get("failure_code")))throw conflict("CONSULTATION_TURN_FAILED");
        return draft(row,locked);
    }
    @Transactional public boolean cancelled(long user,Draft draft){
        lock(user,draft.consultation());
        return jdbc.queryForObject("SELECT COUNT(*) FROM exploration_consultation_turn t JOIN exploration_generation_task g ON g.context_id=? AND g.mutation_key=t.generation_mutation WHERE t.id=? AND t.state='FAILED' AND t.failure_code='CANCELLED' AND g.state='CANCELLED'",Integer.class,draft.context(),draft.id())==1;
    }
    @Transactional public JsonNode replay(long user,long consultation,String key,String question){
        validate(key,question);var locked=lock(user,consultation);verified(locked,null);
        var rows=byKey(consultation,key);
        if(rows.isEmpty())return null;var turn=rows.get(0);sameQuestion(turn,question);
        if(turn.get("state").equals("FAILED"))throw conflict("CONSULTATION_TURN_FAILED");
        return turn.get("state").equals("COMPLETED")?json.read(turn.get("reply_json").toString()):null;
    }
    private void sameQuestion(Map<String,Object> turn,String question){if(!request(turn).path("input").path("conversation").path("question").asText().equals(question))throw conflict("IDEMPOTENCY_CONFLICT");}
    /** Called in the same transaction as OwnedGenerationStore.reserveModel. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Draft prepare(long user,long consultation,String key,String question){
        validate(key,question);var locked=lock(user,consultation);var prior=verified(locked,null);
        var existing=byKey(consultation,key);
        if(!existing.isEmpty()){
            var row=existing.get(0);sameQuestion(row,question);
            if(row.get("state").equals("FAILED"))throw conflict("CONSULTATION_TURN_FAILED");
            return draft(row,locked);
        }
        releaseTerminal(consultation);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM exploration_consultation_turn WHERE consultation_id=? AND state='PENDING'",Integer.class,consultation)>0)throw conflict("CONSULTATION_BUSY");
        long ordinal=jdbc.queryForObject("SELECT COALESCE(MAX(turn_no),0)+1 FROM exploration_consultation_turn WHERE consultation_id=?",Long.class,consultation);
        String mutation="chat_"+UUID.randomUUID();var r=adapter.buildConversation(locked.snapshot(),mutation,conversation(prior,question));
        jdbc.update("INSERT INTO exploration_consultation_turn(consultation_id,turn_no,mutation_hash,generation_mutation,request_json,request_hash,base_revision) VALUES (?,?,?,?,?,?,?)",consultation,ordinal,json.hash(key),mutation,r.toString(),json.hash(r),locked.session().get("revision"));
        return draft(jdbc.queryForMap("SELECT * FROM exploration_consultation_turn WHERE generation_mutation=?",mutation),locked);
    }
    private Draft draft(Map<String,Object> row,Locked locked){return new Draft(number(row,"id"),number(locked.session(),"id"),number(locked.session(),"context_id"),row.get("generation_mutation").toString(),request(row));}
    private void releaseTerminal(long consultation){
        jdbc.update("UPDATE exploration_generation_task g JOIN exploration_consultation c ON c.context_id=g.context_id JOIN exploration_consultation_turn t ON t.consultation_id=c.id AND t.generation_mutation=g.mutation_key SET g.state='FAILED',g.failure_code='ATTEMPTS_EXHAUSTED',g.lease_token=NULL,g.lease_until=NULL,g.next_attempt_at=NULL WHERE c.id=? AND t.state='PENDING' AND g.state='RUNNING' AND g.attempt>=3 AND g.lease_until<=UTC_TIMESTAMP(6)",consultation);
        jdbc.update("UPDATE exploration_consultation_turn t JOIN exploration_consultation c ON c.id=t.consultation_id JOIN exploration_generation_task g ON g.context_id=c.context_id AND g.mutation_key=t.generation_mutation AND g.purpose='CONSULTATION' SET t.state='FAILED',t.failure_code=IF(g.state='CANCELLED','CANCELLED','GENERATION_FAILED') WHERE t.consultation_id=? AND t.state='PENDING' AND g.state IN ('FAILED','CANCELLED')",consultation);
    }
    @Transactional public void releaseTerminal(long user,Draft draft){lock(user,draft.consultation());releaseTerminal(draft.consultation());}
    /** Reconstructs the original question/history from stored owned data at each generation fence. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void verifyRequest(long user,long context,JsonNode expected,boolean pending){
        var rows=jdbc.queryForList("SELECT t.* FROM exploration_consultation_turn t JOIN exploration_consultation c ON c.id=t.consultation_id WHERE c.context_id=? AND t.generation_mutation=?",context,expected.path("requestId").asText());
        if(rows.size()!=1)throw integrity();var turn=rows.get(0);var locked=lock(user,number(turn,"consultation_id"));
        rebuilt(turn,locked.snapshot(),verified(locked,number(turn,"turn_no")));
        if(!request(turn).equals(expected))throw integrity();
        if(pending&&(!turn.get("state").equals("PENDING")||number(turn,"base_revision")!=number(locked.session(),"revision")))throw conflict("CONSULTATION_TURN_FAILED");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelPending(long context,String mutation){
        var rows=jdbc.queryForList("SELECT t.* FROM exploration_consultation_turn t JOIN exploration_consultation c ON c.id=t.consultation_id WHERE c.context_id=? AND t.generation_mutation=? FOR UPDATE",context,mutation);
        if(rows.isEmpty())return;
        if(rows.get(0).get("state").equals("COMPLETED"))throw conflict("CONSULTATION_ALREADY_COMPLETED");
        jdbc.update("UPDATE exploration_consultation_turn SET state='FAILED',failure_code='CANCELLED' WHERE id=? AND state='PENDING'",rows.get(0).get("id"));
    }
    /** Joins the authoritative generation completion transaction, including both message inserts. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(long user,long context,JsonNode original,JsonNode proof){
        verifyRequest(user,context,original,true);
        var turn=jdbc.queryForMap("SELECT * FROM exploration_consultation_turn WHERE generation_mutation=? FOR UPDATE",original.path("requestId").asText());
        var generation=jdbc.queryForMap("SELECT * FROM exploration_generation_task WHERE user_id=? AND context_id=? AND mutation_key=? FOR UPDATE",user,context,turn.get("generation_mutation"));
        boolean insufficient=proof.path("kind").asText().equals("NO_APPROVED_EVIDENCE");
        if(!json.read(generation.get("request_json").toString()).equals(original)||!generation.get("execution_mode").equals("MODEL"))throw integrity();
        if(insufficient){if(!generation.get("state").equals("FAILED")||!"EVIDENCE_UNAVAILABLE".equals(generation.get("failure_code")))throw integrity();}
        else if(!generation.get("state").equals("MODEL_STORED")||!json.hex(proof).equals(generation.get("result_hash"))||!json.read(generation.get("result_json").toString()).equals(proof))throw integrity();
        var data=projection(original,proof);var reply=json.object();
        for(int i=0;i<2;i++){
            var m=data.get(i);
            jdbc.update("INSERT INTO exploration_consultation_message(turn_id,role,content,sources_json,evidence_status,message_hash) VALUES (?,?,?,?,?,?)",turn.get("id"),m.path("role").asText(),m.path("content").asText(),m.path("sources").toString(),m.path("evidenceStatus").textValue(),new byte[32]);
            var row=jdbc.queryForMap("SELECT id,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS created FROM exploration_consultation_message WHERE turn_id=? AND role=?",turn.get("id"),m.path("role").asText());
            m.put("id",number(row,"id")).put("createdAt",row.get("created").toString());
            jdbc.update("UPDATE exploration_consultation_message SET message_hash=? WHERE id=?",json.hash(m),row.get("id"));
            reply.set(i==0?"userMessage":"assistantMessage",m);
        }
        jdbc.update("UPDATE exploration_consultation_turn SET state='COMPLETED',reply_json=?,reply_hash=?,proof_json=?,proof_hash=?,completed_at=UTC_TIMESTAMP(6) WHERE id=? AND state='PENDING'",reply.toString(),json.hash(reply),proof.toString(),json.hash(proof),turn.get("id"));
        if(jdbc.update("UPDATE exploration_consultation SET revision=revision+1 WHERE id=? AND revision=?",turn.get("consultation_id"),turn.get("base_revision"))!=1)throw integrity();
    }
    private List<ObjectNode> projection(JsonNode original,JsonNode proof){
        var user=json.object().put("role","user").put("content",original.path("input").path("conversation").path("question").asText());user.putArray("sources");user.putNull("evidenceStatus");
        var assistant=json.object().put("role","assistant");var sources=assistant.putArray("sources");
        if(proof.path("kind").asText().equals("NO_APPROVED_EVIDENCE")){
            if(proof.size()!=2||!proof.path("inputHash").equals(original.path("inputHash")))throw integrity();
            assistant.put("content","승인된 근거가 부족해 이 질문에 답변할 수 없습니다. 확인할 자료를 보완한 뒤 다시 질문해 주세요.").put("evidenceStatus","INSUFFICIENT");
        }else{
            JsonNode bound=proof.path("boundRequest"),response=proof.path("response");
            try{contract.response(bound,response);}catch(ExplorationAiContract.Invalid e){throw integrity();}
            for(String field:List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!original.path(field).equals(bound.path(field)))throw integrity();
            if(!proof.path("mode").asText().equals("MODEL")||!proof.path("generationAuthorized").asBoolean()||!proof.path("originalInputHash").equals(original.path("inputHash")))throw integrity();
            if(response.path("status").asText().equals("SUCCEEDED")){
                var refs=bound.path("evidence").path("referenceKeys");var names=bound.path("input").path("candidateNames");
                String target=bound.path("input").path("targetName").textValue();
                if(refs.size()!=(target==null?names.size():1))throw integrity();
                assistant.put("content",response.path("result").path("summary").asText()).put("evidenceStatus","GROUNDED");
                for(JsonNode ref:response.path("result").path("referenceKeys")){
                    int index=-1;for(int i=0;i<refs.size();i++)if(ref.equals(refs.get(i)))index=i;
                    if(index<0)throw integrity();
                    sources.addObject().put("kind","MAJOR_PROFILE").put("title",(target==null?names.get(index).asText():target)+" 승인 전공 자료").put("version",bound.path("evidence").path("datasetVersion").asText());
                }
            }else assistant.put("content","이 질문은 현재 탐색 상담에서 답변하기 어렵습니다. 결과 후보나 탐색 활동에 관한 질문으로 다시 확인해 주세요.").put("evidenceStatus","INSUFFICIENT");
        }
        return List.of(user,assistant);
    }
}
