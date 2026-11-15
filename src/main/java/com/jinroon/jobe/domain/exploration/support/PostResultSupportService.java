package com.jinroon.jobe.domain.exploration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.entity.*;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.exploration.service.*;
import com.jinroon.jobe.domain.user.service.UserWriteGuard;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostResultSupportService {
    private final UserWriteGuard users;
    private final ExplorationSessionRepository sessions;
    private final ExplorationResultRepository results;
    private final ExplorationIdempotencyRepository requests;
    private final ExplorationResultStore resultStore;
    private final ExplorationRuntimePolicy policy;
    private final ExplorationRuntimeCatalog catalogs;
    private final ExplorationJson json;
    private final JdbcTemplate jdbc;

    public record Reply(int status,JsonNode body) {}
    private record Context(ExplorationResult result,ExplorationSession session,JsonNode snapshot) {}
    private record Post(long id,long revision,String purpose,String target,JsonNode context,String hash) {}
    private record Issued(long id,String questionId,String version,int ordinal,boolean effective,JsonNode snapshot,String hash) {}

    @Transactional(readOnly=true)
    public JsonNode get(long userId,long resultId) {
        Context context=owned(userId,resultId,false);
        Post post=findPost(resultId,false);
        if(post==null){var response=json.object();response.put("resultId",resultId);response.put("initialized",false);response.put("supportRevision",0);response.set("allowedPurposes",allowedPurposes(context));return response;}
        return view(context,post,frozen(context));
    }

    @Transactional
    public Reply select(long userId,long resultId,String key,long expected,String purpose,String target) {
        validateKey(key);users.requireActive(userId);
        Context context=owned(userId,resultId,true);
        ObjectNode body=json.object();body.put("resultId",resultId);body.put("purpose",purpose);body.put("targetMajorCode",target);body.put("expectedSupportRevision",expected);
        var request=reserve(userId,"SUPPORT_SELECT",resultId,key,json.hash(body));
        if(request.getResponseStatus()!=null)return replay(request);
        Post post=findPost(resultId,true);
        checkRevision(post==null?0:post.revision(),expected);
        validateSelection(context,purpose,target);
        var frozen=frozen(context);
        boolean changed=post==null || !post.purpose().equals(purpose) || !Objects.equals(post.target(),target);
        if(post==null) {
            ObjectNode reference=json.object();reference.put("bundleHash",frozen.hash());reference.put("resultInputHash",context.snapshot().path("inputHash").asText());
            reference.set("versions",frozen.payload().path("versions").deepCopy());
            jdbc.update("INSERT INTO exploration_post_result(result_id,revision,purpose,target_major_code,support_json,context_hash) VALUES (?,0,?,?,?,?)",
                    resultId,purpose,target,json.write(reference),json.hex(reference));
            post=findPost(resultId,true);
        }
        validateContext(context,post,frozen);
        List<Issued> history=history(post,frozen);
        List<BranchCatalog.Key> desired=desired(frozen,purpose);
        Set<String> selected=new HashSet<>();desired.forEach(q->selected.add(q.questionId()));
        Set<String> seen=new HashSet<>();history.forEach(q->seen.add(q.questionId()));
        for(Issued q:history)if(q.effective()!=selected.contains(q.questionId())) {
            jdbc.update("UPDATE exploration_support_issued SET effective=? WHERE id=?",selected.contains(q.questionId()),q.id());changed=true;
        }
        int ordinal=history.size();
        for(var q:desired)if(!seen.contains(q.questionId()) && ordinal<8) {
            JsonNode snapshot=catalogs.questionSnapshot(frozen,q);
            jdbc.update("INSERT INTO exploration_support_issued(post_result_id,question_id,question_version,ordinal,effective,snapshot_json,snapshot_hash,shown_at) VALUES (?,?,?,?,true,?,?,?)",
                    post.id(),q.questionId(),q.questionVersion(),++ordinal,json.write(snapshot),json.hex(snapshot),LocalDateTime.now());changed=true;
        }
        long revision=post.revision()+(changed?1:0);
        jdbc.update("UPDATE exploration_post_result SET revision=?,purpose=?,target_major_code=? WHERE id=?",revision,purpose,target,post.id());
        return success(request,view(context,findPost(resultId,false),frozen));
    }

    @Transactional
    public Reply answer(long userId,long resultId,long issuedId,String key,long expected,String code,String mutationId) {
        validateKey(key);validateKey(mutationId);users.requireActive(userId);
        Context context=owned(userId,resultId,true);
        Post post=findPost(resultId,true);
        if(post==null || jdbc.queryForList("SELECT id FROM exploration_support_issued WHERE id=? AND post_result_id=?",Long.class,issuedId,post.id()).isEmpty())throw notFound();
        byte[] hash=json.hash(Map.of("resultId",resultId,"issuedId",issuedId,"expectedSupportRevision",expected,"optionCode",code,"clientMutationId",mutationId));
        var request=reserve(userId,"SUPPORT_ANSWER",issuedId,key,hash);
        if(request.getResponseStatus()!=null)return replay(request);
        var mutations=jdbc.queryForList("SELECT mutation_request_hash,mutation_response_json FROM exploration_support_answer WHERE issued_id=? AND client_mutation_id=?",issuedId,mutationId);
        if(!mutations.isEmpty()) {
            if(!Arrays.equals((byte[])mutations.get(0).get("mutation_request_hash"),hash))throw conflict();
            return success(request,json.read(mutations.get(0).get("mutation_response_json").toString()));
        }
        checkRevision(post.revision(),expected);
        var frozen=frozen(context);validateContext(context,post,frozen);
        List<Issued> history=history(post,frozen);
        Issued question=history.stream().filter(q->q.id()==issuedId).findFirst().orElseThrow(PostResultSupportService::notFound);
        if(!question.effective())throw new ExplorationApiException(422,"QUESTION_NOT_EFFECTIVE","현재 지원 목적에서 답변할 수 없는 문항입니다.");
        boolean allowed=false;for(JsonNode option:question.snapshot().path("options"))if(option.path("option_code").asText().equals(code))allowed=true;
        if(!allowed)throw new ExplorationApiException(422,"OPTION_NOT_ISSUED","발급된 지원 선택지가 아닙니다.");
        var current=currentAnswers(post,history);
        int next=current.containsKey(issuedId)?((Number)current.get(issuedId).get("answer_revision")).intValue()+1:1;
        jdbc.update("UPDATE exploration_support_answer SET is_current=false WHERE issued_id=? AND is_current=true",issuedId);
        jdbc.update("INSERT INTO exploration_support_answer(issued_id,answer_revision,option_code,is_current,target_major_code,client_mutation_id,mutation_request_hash,mutation_response_json,answered_at) VALUES (?,?,?,true,?,?,?,JSON_OBJECT(),?)",
                issuedId,next,code,post.target(),mutationId,hash,LocalDateTime.now());
        jdbc.update("UPDATE exploration_post_result SET revision=revision+1 WHERE id=?",post.id());
        JsonNode response=view(context,findPost(resultId,false),frozen);
        jdbc.update("UPDATE exploration_support_answer SET mutation_response_json=? WHERE issued_id=? AND client_mutation_id=?",json.write(response),issuedId,mutationId);
        return success(request,response);
    }

    private Context owned(long userId,long resultId,boolean lock) {
        var result=results.findById(resultId).orElseThrow(PostResultSupportService::notFound);
        var session=(lock?sessions.findByIdForUpdate(result.getSessionId()):sessions.findById(result.getSessionId()))
                .filter(s->s.isOwnedBy(userId)).orElseThrow(PostResultSupportService::notFound);
        policy.authorizeExisting(userId,session.getRunMode());
        if(session.getStatus()!=ExplorationSessionStatus.completed)throw ExplorationApiException.integrity();
        return new Context(result,session,resultStore.verifiedSnapshot(result));
    }
    private ExplorationRuntimeCatalog.Frozen frozen(Context context) {
        var frozen=catalogs.load(context.session().getId());
        if(!frozen.hash().equals(context.snapshot().path("bundleHash").asText()) || frozen.payload().path("rules").path("caps").path("post_result").asInt()!=8)throw ExplorationApiException.integrity();
        return frozen;
    }
    private void validateSelection(Context context,String purpose,String target) {
        if(!Set.of("PLAN","ACTIVITY","BOTH").contains(purpose))throw new ExplorationApiException(400,"INVALID_REQUEST","지원 목적을 확인해 주세요.");
        if(context.snapshot().path("status").asText().equals("NO_SUPPORTED_CANDIDATE") && !purpose.equals("ACTIVITY"))
            throw new ExplorationApiException(409,"PLAN_TARGET_UNAVAILABLE","후보가 없는 결과에서는 활동 지원만 선택할 수 있습니다.");
        if(target!=null) {
            boolean found=false;for(JsonNode major:context.snapshot().path("rankedCandidates"))if(major.path("majorCode").asText().equals(target))found=true;
            if(!found)throw new ExplorationApiException(422,"TARGET_MAJOR_NOT_IN_RESULT","고정된 결과 후보에 없는 목표 전공입니다.");
        }
    }
    private Post findPost(long resultId,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM exploration_post_result WHERE result_id=?"+(lock?" FOR UPDATE":""),resultId);
        if(rows.isEmpty())return null;var row=rows.get(0);
        return new Post(number(row,"id"),number(row,"revision"),row.get("purpose").toString(),(String)row.get("target_major_code"),json.read(row.get("support_json").toString()),row.get("context_hash").toString());
    }
    private void validateContext(Context context,Post post,ExplorationRuntimeCatalog.Frozen frozen) {
        if(!post.hash().equals(json.hex(post.context())) || !frozen.hash().equals(post.context().path("bundleHash").asText())
                || !context.snapshot().path("inputHash").asText().equals(post.context().path("resultInputHash").asText()))throw ExplorationApiException.integrity();
    }
    private List<BranchCatalog.Key> desired(ExplorationRuntimeCatalog.Frozen frozen,String purpose) {
        try{return new PostResultQuestionPlanner().desired(frozen.branch(),purpose);}catch(IllegalArgumentException e){throw ExplorationApiException.integrity();}
    }
    private List<Issued> history(Post post,ExplorationRuntimeCatalog.Frozen frozen) {
        List<Issued> out=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT * FROM exploration_support_issued WHERE post_result_id=? ORDER BY ordinal",post.id())) {
            JsonNode snapshot=json.read(row.get("snapshot_json").toString());String hash=row.get("snapshot_hash").toString();
            String id=row.get("question_id").toString(),version=row.get("question_version").toString();
            int ordinal=((Number)row.get("ordinal")).intValue();
            String expected;
            try{expected=json.hex(catalogs.questionSnapshot(frozen,new BranchCatalog.Key(id,version,"","")));}catch(IllegalArgumentException e){throw ExplorationApiException.integrity();}
            if(ordinal!=out.size()+1 || ordinal>8 || !hash.equals(json.hex(snapshot)) || !hash.equals(expected))throw ExplorationApiException.integrity();
            out.add(new Issued(number(row,"id"),id,version,ordinal,(Boolean)row.get("effective"),snapshot,hash));
        }
        return out;
    }
    private Map<Long,Map<String,Object>> currentAnswers(Post post,List<Issued> history) {
        Map<Long,Map<String,Object>> current=new HashMap<>();
        jdbc.queryForList("SELECT a.* FROM exploration_support_answer a JOIN exploration_support_issued q ON q.id=a.issued_id WHERE q.post_result_id=? AND a.is_current=true",post.id())
                .forEach(a->current.put(number(a,"issued_id"),a));
        for(var latest:jdbc.queryForList("SELECT a.issued_id,MAX(a.answer_revision) latest,SUM(a.is_current) current_count FROM exploration_support_answer a JOIN exploration_support_issued q ON q.id=a.issued_id WHERE q.post_result_id=? GROUP BY a.issued_id",post.id())) {
            long id=number(latest,"issued_id");
            if(number(latest,"current_count")!=1 || !current.containsKey(id) || number(current.get(id),"answer_revision")!=number(latest,"latest"))throw ExplorationApiException.integrity();
        }
        for(Issued q:history)if(current.containsKey(q.id())) {
            boolean valid=false;for(JsonNode option:q.snapshot().path("options"))if(option.path("option_code").asText().equals(current.get(q.id()).get("option_code")))valid=true;
            if(!valid)throw ExplorationApiException.integrity();
        }
        return current;
    }
    private JsonNode view(Context context,Post post,ExplorationRuntimeCatalog.Frozen frozen) {
        validateContext(context,post,frozen);validateSelection(context,post.purpose(),post.target());
        List<Issued> history=history(post,frozen);var current=currentAnswers(post,history);
        var wanted=desired(frozen,post.purpose());Set<String> ids=new HashSet<>();wanted.forEach(k->ids.add(k.questionId()));
        Set<String> seen=new HashSet<>();history.forEach(q->seen.add(q.questionId()));
        var omitted=wanted.stream().filter(k->!seen.contains(k.questionId())).map(BranchCatalog.Key::questionId).toList();
        ObjectNode response=json.object();response.put("resultId",context.result().getId());response.put("initialized",true);
        response.put("supportRevision",post.revision());response.put("purpose",post.purpose());response.put("targetMajorCode",post.target());
        response.set("allowedPurposes",allowedPurposes(context));
        response.put("runMode",context.session().getRunMode().name());response.put("testOnly",true);response.put("questionsOptional",true);
        response.put("targetSelectionRequired",!post.purpose().equals("ACTIVITY") && post.target()==null);
        response.set("versions",post.context().path("versions").deepCopy());
        var rows=response.putArray("issued");int active=0,answered=0;
        for(Issued q:history) {
            if(q.effective()!=ids.contains(q.questionId()))throw ExplorationApiException.integrity();
            var item=rows.addObject();var question=item.putObject("question");
            question.put("issuedId",q.id());question.put("questionId",q.questionId());question.put("questionVersion",q.version());question.put("ordinal",q.ordinal());
            question.put("text",q.snapshot().path("question").path("question_text").asText());question.put("required",false);
            var options=question.putArray("options");List<JsonNode> ordered=new ArrayList<>();q.snapshot().path("options").forEach(ordered::add);
            ordered.sort(Comparator.comparingInt(o->o.path("option_order").asInt()));
            for(JsonNode o:ordered){var option=options.addObject();option.put("code",o.path("option_code").asText());option.put("text",o.path("option_text").asText());option.put("order",o.path("option_order").asInt());}
            item.put("effective",q.effective());var answer=current.get(q.id());
            boolean matches=answer!=null && (q.snapshot().path("question").path("module_code").asText().equals("EXPLORATION_ACTIVITY") || Objects.equals(answer.get("target_major_code"),post.target()));
            item.put("answerCode",answer==null?null:answer.get("option_code").toString());item.set("answerRevision",json.tree(answer==null?null:answer.get("answer_revision")));
            item.put("answerTargetMajorCode",answer==null?null:(String)answer.get("target_major_code"));item.put("answerMatchesTarget",matches);
            item.put("answerApplicable",q.effective()&&matches);
            if(q.effective()){active++;if(matches)answered++;}
        }
        var progress=response.putObject("progress");progress.put("issued",history.size());progress.put("active",active);progress.put("answered",answered);progress.put("maximum",8);progress.put("remainingMax",8-history.size());
        response.put("budgetLimited",!omitted.isEmpty());response.set("omittedQuestionIds",json.tree(omitted));
        response.put("nextAction",active==0&&!omitted.isEmpty()?"INFO_LIMIT_REACHED":answered<active?"ANSWER":"DONE");
        response.put("activityAvailability","NOT_EVALUATED");response.put("activitiesEndpoint","/api/exploration/v1/results/"+context.result().getId()+"/activities");response.put("planGenerationAvailable",false);
        return response;
    }
    private JsonNode allowedPurposes(Context context){return json.tree(context.snapshot().path("status").asText().equals("NO_SUPPORTED_CANDIDATE")?List.of("ACTIVITY"):List.of("PLAN","ACTIVITY","BOTH"));}
    private ExplorationIdempotency reserve(long user,String operation,long resource,String key,byte[] hash) {
        var old=requests.findByUserIdAndOperationAndResourceIdAndIdempotencyKey(user,operation,resource,key);
        if(old.isPresent()){if(!old.get().hasSameRequest(hash))throw conflict();if(old.get().getResponseStatus()==null)throw ExplorationApiException.integrity();return old.get();}
        return requests.save(ExplorationIdempotency.reserve(user,operation,resource,key,hash));
    }
    private Reply replay(ExplorationIdempotency request){return new Reply(request.getResponseStatus(),json.read(request.getResponseJson()));}
    private Reply success(ExplorationIdempotency request,JsonNode body){String stored=json.write(body);request.storeSuccess(200,stored);return new Reply(200,json.read(stored));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static void validateKey(String key){if(key==null || !key.matches("[!-~]{1,128}"))throw new ExplorationApiException(400,"INVALID_REQUEST","멱등/변경 식별자를 확인해 주세요.");}
    private static void checkRevision(long current,long expected){if(current!=expected)throw new ExplorationApiException(409,"STALE_REVISION","최신 지원 정보를 조회한 뒤 다시 요청해 주세요.");}
    private static ExplorationApiException notFound(){return new ExplorationApiException(404,"RESOURCE_NOT_FOUND","지원 대상 결과 또는 문항을 찾을 수 없습니다.");}
    private static ExplorationApiException conflict(){return new ExplorationApiException(409,"IDEMPOTENCY_CONFLICT","같은 식별자의 요청 내용이 다릅니다.");}
}
