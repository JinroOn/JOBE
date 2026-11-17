package com.jinroon.jobe.domain.exploration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.entity.*;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.exploration.service.*;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import com.jinroon.jobe.domain.user.service.UserWriteGuard;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Typed immutable prepared input, isolated from legacy consultation/plan IDs and from AI invocation. */
@Service
@RequiredArgsConstructor
public class ResultContextService {
    public static final String CONTRACT="EXPLORATION_RESULT_CONTEXT_V1_0";
    private final UserWriteGuard users;
    private final ExplorationSessionRepository sessions;
    private final ExplorationResultRepository results;
    private final ExplorationIdempotencyRepository requests;
    private final ExplorationResultStore resultStore;
    private final ExplorationRuntimePolicy policy;
    private final ExplorationRuntimeCatalog catalogs;
    private final PostResultSupportService support;
    private final ExplorationJson json;
    private final JdbcTemplate jdbc;
    public record Reply(int status,JsonNode body){}
    private record Source(ExplorationResult result,ExplorationSession session,JsonNode snapshot){}
    public record LockedInput(long contextId,String contextType,JsonNode snapshot){}
    /** Validation without preparing personal data or invoking AI. Used before a new plan request. */
    @Transactional
    public void validatePlanRequest(long user,long resultId,Long expected,String target){
        users.requireActive(user);Source source=owned(user,resultId,true);JsonNode state=support.get(user,resultId);
        long revision=state.path("supportRevision").asLong();
        if(expected!=null&&expected!=revision)throw new ExplorationApiException(409,"STALE_REVISION","최신 지원 상태를 조회한 뒤 다시 준비해 주세요.");
        validateTarget(source.snapshot(),state,"PLAN",target);
    }

    @Transactional
    public Reply prepare(long user,long resultId,String key,String type,long expected,String target){
        validateKey(key);validateType(type);users.requireActive(user);Source source=owned(user,resultId,true);
        ObjectNode body=json.object();body.put("contextType",type);body.put("expectedSupportRevision",expected);body.put("targetMajorCode",target);
        var request=reserve(user,resultId,key,json.hash(body));
        if(request.getResponseStatus()!=null){JsonNode response=json.read(request.getResponseJson());checked(row(response.path("contextId").asLong(),false),source);return new Reply(request.getResponseStatus(),response);}
        // Same user/session locks as support mutations; this current read explicitly pins the support revision.
        var post=jdbc.queryForList("SELECT id,revision FROM exploration_post_result WHERE result_id=? FOR UPDATE",resultId);
        long revision=post.isEmpty()?0:number(post.get(0),"revision");
        if(revision!=expected)throw new ExplorationApiException(409,"STALE_REVISION","최신 지원 상태를 조회한 뒤 다시 준비해 주세요.");
        JsonNode state=support.get(user,resultId);if(state.path("supportRevision").asLong()!=revision)throw ExplorationApiException.integrity();
        validateTarget(source.snapshot(),state,type,target);
        var frozen=catalogs.load(source.session().getId());
        if(!frozen.hash().equals(source.snapshot().path("bundleHash").asText())||!json.hex(frozen.payload().path("versions")).equals(json.hex(source.snapshot().path("versions"))))throw ExplorationApiException.integrity();
        ObjectNode input=json.object();input.put("contractVersion",CONTRACT);input.put("resultType","EXPLORATION_V1");input.put("contextType",type);input.put("targetMajorCode",target);
        var provenance=input.putObject("source");provenance.put("resultId",resultId);provenance.put("sessionId",source.session().getId());provenance.put("resultHash",json.hex(source.snapshot()));
        provenance.put("resultContractVersion",source.result().getResultContractVersion());provenance.put("inputHash",source.snapshot().path("inputHash").asText());provenance.put("bundleHash",frozen.hash());provenance.set("versions",source.snapshot().path("versions").deepCopy());
        if(target==null)input.putNull("targetMajor");else{
            JsonNode selected=null;for(JsonNode m:frozen.payload().path("mapping").path("majors"))if(target.equals(m.path("major_code").asText()))selected=m;
            if(selected==null)throw ExplorationApiException.integrity();var profile=input.putObject("targetMajor");
            for(String field:List.of("major_code","major_name","exploration_group","interest_structure","primary_interest_axes","secondary_interest_axes","activity_axes","learning_content_sets","domain_review_status"))profile.set(field,selected.path(field).deepCopy());
            profile.put("usage","DEV_MAPPING_NOT_APPROVED_AI_EVIDENCE");
        }
        // Private normalized input, not a public result DTO and not yet an AI provider request.
        var diagnosis=input.putObject("result");for(String field:List.of("status","completionReason","majorSummaries","groupSummaries","evidenceLevel","flags"))diagnosis.set(field,source.snapshot().path(field).deepCopy());
        var pinned=input.putObject("support");pinned.put("initialized",state.path("initialized").asBoolean());pinned.put("revision",revision);pinned.put("purpose",state.path("purpose").textValue());pinned.put("targetMajorCode",state.path("targetMajorCode").textValue());
        pinned.put("questionsOptional",true);pinned.set("collectionProgress",state.has("progress")?state.path("progress").deepCopy():json.tree(null));pinned.put("collectionBudgetLimited",state.path("budgetLimited").asBoolean());pinned.set("omittedQuestionIds",state.has("omittedQuestionIds")?state.path("omittedQuestionIds").deepCopy():json.array());
        var answers=pinned.putArray("appliedAnswers");
        for(JsonNode issued:state.path("issued"))if(issued.path("answerApplicable").asBoolean()) {
            String q=issued.path("question").path("questionId").asText();var question=frozen.branch().question(q);boolean generic=question.module().equals("EXPLORATION_ACTIVITY");
            if(!generic&&!Objects.equals(issued.path("answerTargetMajorCode").textValue(),target))continue;
            var identity=frozen.branch().key(question,"");var snapshot=catalogs.questionSnapshot(frozen,identity);
            var option=frozen.branch().options(identity).stream().filter(o->o.code().equals(issued.path("answerCode").asText())).findFirst().orElseThrow(ExplorationApiException::integrity);
            JsonNode choice=null;for(JsonNode value:snapshot.path("options"))if(value.path("option_code").asText().equals(option.code()))choice=value;
            if(choice==null)throw ExplorationApiException.integrity();
            var answer=answers.addObject();answer.put("issuedId",issued.path("question").path("issuedId").asLong());answer.put("questionId",q);answer.put("questionVersion",question.version());answer.put("snapshotHash",json.hex(snapshot));
            answer.put("module",question.module());answer.put("optionCode",option.code());answer.put("meaningCode",option.meaning());answer.put("unknown",option.unknown());answer.put("answerText",choice.path("option_text").asText());
            answer.set("answerRevision",issued.path("answerRevision").deepCopy());answer.put("answerTargetMajorCode",issued.path("answerTargetMajorCode").textValue());
        }
        input.put("activitySelectionStatus","NOT_SELECTED");input.put("generationStatus","NOT_REQUESTED");input.put("runMode",source.session().getRunMode().name());input.put("testOnly",true);
        byte[] hash=json.hash(input);
        jdbc.update("INSERT INTO exploration_result_context(result_type,result_id,context_type,target_major_code,support_revision,contract_version,result_hash,input_hash,snapshot_json,created_at) VALUES ('EXPLORATION_V1',?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                resultId,type,target,revision,CONTRACT,json.hex(source.snapshot()),hash,json.write(input),LocalDateTime.now());
        var rows=jdbc.queryForList("SELECT * FROM exploration_result_context WHERE result_id=? AND context_type=? AND input_hash=? FOR UPDATE",resultId,type,hash);
        if(rows.size()!=1)throw ExplorationApiException.integrity();var stored=rows.get(0);JsonNode verified=checked(stored,source);JsonNode response=view(stored,verified);
        String serialized=json.write(response);request.storeSuccess(201,serialized);return new Reply(201,json.read(serialized));
    }

    @Transactional(readOnly=true)
    public JsonNode get(long user,long contextId){var row=row(contextId,false);Source source=owned(user,number(row,"result_id"),false);return view(row,checked(row,source));}

    @Transactional(readOnly=true)
    public JsonNode list(long user,long resultId,int limit,Long before){
        Source source=owned(user,resultId,false);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM exploration_result_context WHERE result_id=?"+(before==null?"":" AND id<?")+" ORDER BY id DESC LIMIT ?",before==null?new Object[]{resultId,limit+1}:new Object[]{resultId,before,limit+1});
        var out=json.object();var items=out.putArray("items");for(var row:rows.subList(0,Math.min(rows.size(),limit)))items.add(view(row,checked(row,source)));
        boolean more=rows.size()>limit;out.put("hasMore",more);out.set("nextBeforeContextId",json.tree(more?number(rows.get(limit-1),"id"):null));return out;
    }

    /** Short ownership/snapshot read for preparation. No external call occurs while these locks are held. */
    @Transactional
    public LockedInput readForAdapter(long user,long contextId,String type){return lockForUse(user,contextId,type);}

    /** Future callback must call this inside its final persistence transaction, after the external call. */
    @Transactional(propagation=Propagation.MANDATORY)
    public LockedInput lockForUse(long user,long contextId,String type){
        validateType(type);users.requireActive(user);var reference=row(contextId,false);Source source=owned(user,number(reference,"result_id"),true);
        var locked=row(contextId,true);if(!type.equals(locked.get("context_type")))throw new ExplorationApiException(409,"CONTEXT_TYPE_MISMATCH","입력 컨텍스트 용도가 다릅니다.");
        return new LockedInput(contextId,type,checked(locked,source).deepCopy());
    }
    private Source owned(long user,long resultId,boolean lock){var result=results.findById(resultId).orElseThrow(ResultContextService::missing);var session=(lock?sessions.findByIdForUpdate(result.getSessionId()):sessions.findById(result.getSessionId())).filter(s->s.isOwnedBy(user)).orElseThrow(ResultContextService::missing);policy.authorizeExisting(user,session.getRunMode());if(session.getStatus()!=ExplorationSessionStatus.completed)throw ExplorationApiException.integrity();return new Source(result,session,resultStore.verifiedSnapshot(result));}
    private void validateTarget(JsonNode source,JsonNode state,String type,String target){
        if(type.equals("PLAN")&&source.path("status").asText().equals("NO_SUPPORTED_CANDIDATE"))throw new ExplorationApiException(409,"PLAN_TARGET_UNAVAILABLE","후보가 없는 결과는 계획 입력을 준비할 수 없습니다.");
        if(target!=null){boolean found=false;for(JsonNode m:source.path("rankedCandidates"))if(target.equals(m.path("majorCode").asText()))found=true;if(!found)throw new ExplorationApiException(422,"TARGET_MAJOR_NOT_IN_RESULT","고정 결과 후보에 없는 목표입니다.");}
        if(type.equals("PLAN")){
            if(target==null)throw new ExplorationApiException(422,"TARGET_MAJOR_REQUIRED","계획 입력에는 목표 전공이 필요합니다.");
            if(!state.path("initialized").asBoolean()||!Set.of("PLAN","BOTH").contains(state.path("purpose").asText()))throw new ExplorationApiException(409,"PLAN_SUPPORT_REQUIRED","PLAN 또는 BOTH 지원 선택이 필요합니다.");
            if(!target.equals(state.path("targetMajorCode").textValue()))throw new ExplorationApiException(409,"SUPPORT_TARGET_MISMATCH","선택한 지원 목표와 계획 목표가 다릅니다.");
        }
    }
    private Map<String,Object> row(long id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM exploration_result_context WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw missing();return rows.get(0);}
    private JsonNode checked(Map<String,Object> row,Source source){
        JsonNode input=json.read(row.get("snapshot_json").toString()),provenance=input.path("source");
        if(!Arrays.equals((byte[])row.get("input_hash"),json.hash(input))||!CONTRACT.equals(row.get("contract_version"))||!CONTRACT.equals(input.path("contractVersion").asText())
                ||!"EXPLORATION_V1".equals(row.get("result_type"))||!"EXPLORATION_V1".equals(input.path("resultType").asText())||!row.get("context_type").equals(input.path("contextType").asText())
                ||number(row,"result_id")!=source.result().getId()||provenance.path("resultId").asLong()!=source.result().getId()||provenance.path("sessionId").asLong()!=source.session().getId()
                ||!json.hex(source.snapshot()).equals(row.get("result_hash"))||!row.get("result_hash").equals(provenance.path("resultHash").asText())
                ||!Objects.equals(row.get("target_major_code"),input.path("targetMajorCode").textValue())||number(row,"support_revision")!=input.path("support").path("revision").asLong()
                ||!json.hex(source.snapshot().path("versions")).equals(json.hex(provenance.path("versions")))
                ||!source.result().getResultContractVersion().equals(provenance.path("resultContractVersion").asText())
                ||!source.snapshot().path("inputHash").asText().equals(provenance.path("inputHash").asText())
                ||!source.snapshot().path("bundleHash").asText().equals(provenance.path("bundleHash").asText())
                ||!source.session().getRunMode().name().equals(input.path("runMode").asText())||!input.path("testOnly").asBoolean()||!"NOT_REQUESTED".equals(input.path("generationStatus").asText()))throw ExplorationApiException.integrity();
        return input;
    }
    private JsonNode view(Map<String,Object> row,JsonNode input){var out=json.object();long id=number(row,"id"),result=number(row,"result_id");out.put("contextId",id);out.put("contextKey","EXPLORATION_V1:CONTEXT:"+id);out.put("contextType",row.get("context_type").toString());out.put("resultType","EXPLORATION_V1");out.put("resultId",result);out.put("resultKey","EXPLORATION_V1:RESULT:"+result);out.put("contractVersion",CONTRACT);out.put("status","PREPARED");out.put("generationStatus","NOT_REQUESTED");out.put("generationAvailable",false);out.put("targetMajorCode",(String)row.get("target_major_code"));out.put("supportRevision",number(row,"support_revision"));out.put("appliedAnswerCount",input.path("support").path("appliedAnswers").size());out.set("versions",input.path("source").path("versions").deepCopy());out.put("inputHash",HexFormat.of().formatHex((byte[])row.get("input_hash")));out.put("createdAt",row.get("created_at").toString());out.put("runMode",input.path("runMode").asText());out.put("testOnly",true);return out;}
    private ExplorationIdempotency reserve(long user,long resultId,String key,byte[] hash){var old=requests.findByUserIdAndOperationAndResourceIdAndIdempotencyKey(user,"CONTEXT_PREPARE",resultId,key);if(old.isPresent()){if(!old.get().hasSameRequest(hash))throw new ExplorationApiException(409,"IDEMPOTENCY_CONFLICT","같은 멱등 키의 요청 내용이 다릅니다.");if(old.get().getResponseStatus()==null)throw ExplorationApiException.integrity();return old.get();}return requests.save(ExplorationIdempotency.reserve(user,"CONTEXT_PREPARE",resultId,key,hash));}
    private static void validateKey(String key){if(key==null||!key.matches("[!-~]{1,128}"))throw new ExplorationApiException(400,"INVALID_REQUEST","멱등 키 형식을 확인해 주세요.");}
    private static void validateType(String type){if(type==null||!Set.of("PLAN","CONSULTATION").contains(type))throw new ExplorationApiException(400,"INVALID_REQUEST","입력 컨텍스트 용도를 확인해 주세요.");}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static ExplorationApiException missing(){return new ExplorationApiException(404,"RESOURCE_NOT_FOUND","입력 컨텍스트 또는 결과를 찾을 수 없습니다.");}
}
