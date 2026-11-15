package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.branching.QuestionBranchEngine;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimeCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ExplorationCompletionApiTest extends ExplorationApiTestSupport {
    @Autowired ExplorationRuntimeCatalog catalogs;
    @Test void fullHttpFlowCompletesAndBothResultRoutesReturnTheSameStoredSnapshot() throws Exception {
        long id = ready(75, false, true);
        JsonNode result = complete(id, revision(id), "complete", 200);
        long rid = result.path("resultId").asLong();
        assertThat(result.path("sessionStatus").asText()).isEqualTo("completed");
        assertThat(result.path("resultContractVersion").asText()).isEqualTo("RESULT_CONTRACT_V1_0");
        assertThat(result.path("status").asText()).isEqualTo("LOW_EVIDENCE");
        assertThat(result.path("completionReason").asText()).isEqualTo("INFO_LIMIT_REACHED");
        assertThat(result.path("majors").size()).isBetween(1,7);
        assertThat(result.path("groups").size()).isBetween(1,3);
        assertThat(result.path("runMode").asText()).isEqualTo("DEV");
        assertThat(result.path("testOnly").asBoolean()).isTrue();
        assertThat(result.path("flags").path("lowDifferentiation").asBoolean()).isTrue();
        assertThat(getJson("/" + id + "/result", owner, 200)).isEqualTo(result);
        assertThat(resultById(rid, owner, 200)).isEqualTo(result);
        assertThat(getJson("/" + id, owner, 200).path("session").path("resultId").asLong()).isEqualTo(rid);
        assertThat(getJson("", owner, 200).get(0).path("resultId").asLong()).isEqualTo(rid);
        assertThat(result.toString()).doesNotContain("inputHash", "bundleHash", "rankedCandidates", "numeric_value", "sourceHashes", "total\"", "topic\"");
        assertThat(result.path("nextActivities").isEmpty()).isTrue();
        assertThat(result.path("activityAvailability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("capabilities").path("supportAvailable").asBoolean()).isFalse();
    }

    @Test void allUnknownIsNormalCompletionWithEmptyCandidatesAndNoInventedZeroScores() throws Exception {
        long id = ready(null, false, false);
        JsonNode result = complete(id, revision(id), "unknown", 200);
        assertThat(result.path("status").asText()).isEqualTo("NO_SUPPORTED_CANDIDATE");
        assertThat(result.path("completionReason").asText()).isEqualTo("NORMAL");
        assertThat(result.path("groups").isEmpty()).isTrue(); assertThat(result.path("majors").isEmpty()).isTrue();
        assertThat(result.path("reasonCodes").toString()).contains("INTEREST_NO_VALID_RESPONSES", "SCREEN_VALID_BELOW_MIN");
        assertThat(result.path("flags").path("stateUnknown").asBoolean()).isTrue();
        JsonNode stored = stored(id);
        assertThat(stored.path("axes").path("interest").path("INT_HUM").path("score").isNull()).isTrue();
        assertThat(stored.path("inputHash").asText()).hasSize(64);
        assertThat(getJson("/" + id, owner, 200).path("session").path("status").asText()).isEqualTo("completed");
    }

    @Test void actualZeroInterestIsDistinguishedFromUnknownWithoutBecomingAnError() throws Exception {
        long id = ready(0, false, false);
        JsonNode result = complete(id, revision(id), "zero", 200);
        assertThat(result.path("status").asText()).isEqualTo("NO_SUPPORTED_CANDIDATE");
        assertThat(result.path("reasonCodes").toString()).contains("NO_POSITIVE_TOPIC_EVIDENCE").doesNotContain("INTEREST_NO_VALID_RESPONSES", "SCREEN_VALID_BELOW_MIN");
        assertThat(stored(id).path("axes").path("interest").path("INT_HUM").path("score").asDouble()).isZero();
    }

    @Test void inconsistentDisplayedAxesHaveReasonCodesWithoutScorePenalty() throws Exception {
        long id = ready(50, true, false);
        JsonNode result = complete(id, revision(id), "inconsistent", 200);
        assertThat(result.path("status").asText()).isEqualTo("LOW_EVIDENCE");
        assertThat(result.path("reasonCodes").toString()).contains("USED_INTEREST_AXIS_INCONSISTENT");
        List<String> codes = new ArrayList<>(); result.path("reasonCodes").forEach(c -> codes.add(c.asText()));
        assertThat(codes).isSorted().doesNotHaveDuplicates();
    }

    @Test void completionRetriesWithNewOrOldKeysReturnTheSameResultDespiteOldRevision() throws Exception {
        long id = ready(null, false, false); long revision = revision(id);
        JsonNode result = complete(id, revision, "complete", 200);
        assertThat(complete(id, revision, "complete", 200)).isEqualTo(result);
        assertThat(complete(id, 0, "new-key", 200)).isEqualTo(result);
        assertThat(complete(id, revision + 1, "complete", 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("exploration_result", "session_id=?", id)).isEqualTo(1);
        assertThat(count("exploration_result_seal", "result_id=?", result.path("resultId").asLong())).isEqualTo(1);
    }

    @Test void missingRequiredAnswersAndPrematureCompletionAreSeparateErrors() throws Exception {
        long id = start("start").path("sessionId").asLong();
        assertThat(complete(id, 0, "empty", 422).path("code").asText()).isEqualTo("STAGE_INCOMPLETE");
        issueAndAnswer(id, 75, false, false);
        issueAndAnswer(id, 75, false, false);
        assertThat(complete(id, revision(id), "early-denied", 409).path("code").asText()).isEqualTo("COMPLETION_NOT_ALLOWED");
        postJson(id + "/advance", owner, "advance", revisionBody(revision(id)), 200);
        JsonNode next = issue(id);
        assertThat(next.path("issuedQuestions").isEmpty()).isFalse();
        assertThat(complete(id, revision(id), "missing", 422).path("code").asText()).isEqualTo("STAGE_INCOMPLETE");
        assertThat(count("exploration_result", "session_id=?", id)).isZero();
    }

    @Test void uncompletedStaleRequestsRollbackReservations() throws Exception {
        long id = ready(null, false, false);
        assertThat(complete(id, 0, "stale", 409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(count("exploration_idempotency", "user_id=? AND idempotency_key='stale'", OWNER)).isZero();
    }

    @Test void completedSessionsRejectAnswerAdvanceNextAndAbandonButCanStartAnotherSession() throws Exception {
        long id = ready(null, false, false); JsonNode q = getJson("/"+id,owner,200).path("issued").get(0).path("question");
        JsonNode result = complete(id, revision(id), "complete", 200);
        long revision = revision(id);
        for (String operation : List.of("next", "advance", "abandon"))
            assertThat(postJson(id + "/" + operation, owner, operation, revisionBody(revision), 409).path("code").asText()).isEqualTo("SESSION_ALREADY_COMPLETED");
        response(put(API+"/"+id+"/answers/"+q.path("issuedId").asLong()).header("Authorization",owner).header("Idempotency-Key","late-answer")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedRevision",revision,"optionCode","UNSURE","clientMutationId","late-answer"))),409);
        assertThat(start("new-session").path("sessionId").asLong()).isNotEqualTo(id);
        assertThat(resultById(result.path("resultId").asLong(),owner,200)).isEqualTo(result);
    }

    @Test void resultsRequireOwnershipAndIncompleteSessionsHaveNoResult() throws Exception {
        long id = ready(null, false, false);
        getJson("/"+id+"/result",owner,404);
        postJson(id+"/complete",other,"foreign",revisionBody(0),404);
        JsonNode result=complete(id,revision(id),"complete",200);long rid=result.path("resultId").asLong();
        getJson("/"+id+"/result",other,404);resultById(rid,other,404);resultById(Long.MAX_VALUE,owner,404);
    }

    @Test void completedResultDoesNotReadOrRecomputeFromAlteredRuntimeCatalog() throws Exception {
        long id=ready(null,false,false);JsonNode result=complete(id,revision(id),"complete",200);
        jdbc.update("UPDATE exploration_runtime_snapshot r JOIN exploration_session_pin p ON r.content_hash=p.snapshot_hash SET r.payload_json=JSON_OBJECT('changed',true) WHERE p.session_id=?",id);
        assertThat(getJson("/"+id+"/result",owner,200)).isEqualTo(result);
        assertThat(complete(id,0,"retry-without-catalog",200)).isEqualTo(result);
    }

    @Test void tamperedResultOrMissingResultFailsClosedEvenForSuccessfulCompleteRetry() throws Exception {
        long id=ready(null,false,false);long rev=revision(id);JsonNode result=complete(id,rev,"complete",200);
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE session_id=?",id);
        assertThat(resultById(result.path("resultId").asLong(),owner,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        complete(id,rev,"complete",503);
        jdbc.update("DELETE FROM exploration_result WHERE session_id=?",id);
        assertThat(complete(id,rev,"complete",503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
    }

    @Test void concurrentDifferentKeysCompleteExactlyOnceAndReturnTheSameResult() throws Exception {
        long id=ready(null,false,false);long rev=revision(id);
        var replies=parallel(()->service.complete(OWNER,id,UUID.randomUUID().toString(),rev));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_result","session_id=?",id)).isEqualTo(1);
        assertThat(revision(id)).isEqualTo(rev+1);
    }

    @Test void concurrentSameKeyCompleteReplaysExactlyOneResult() throws Exception {
        long id=ready(null,false,false);long rev=revision(id);
        var replies=parallel(()->service.complete(OWNER,id,"same-complete",rev));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_idempotency","user_id=? AND operation='COMPLETE'",OWNER)).isEqualTo(1);
    }

    @Test void failedSealInsertRollsBackResultCompletionAndReservationThenSameKeyCanRetry() throws Exception {
        long id=ready(null,false,false);long rev=revision(id);
        jdbc.execute("CREATE TRIGGER test_result_seal_failure BEFORE INSERT ON exploration_result_seal FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic result failure'");
        try {
            complete(id,rev,"retry",500);
            assertThat(count("exploration_result","session_id=?",id)).isZero();
            assertThat(count("exploration_idempotency","user_id=? AND idempotency_key='retry'",OWNER)).isZero();
            assertThat(getJson("/"+id,owner,200).path("session").path("status").asText()).isEqualTo("in_progress");
            assertThat(revision(id)).isEqualTo(rev);
        } finally {jdbc.execute("DROP TRIGGER test_result_seal_failure");}
        complete(id,rev,"retry",200);
    }

    @Test void commitFailureRollsBackResultSealAndSessionCompletion() throws Exception {
        long id=ready(null,false,false);long rev=revision(id);
        jdbc.execute("CREATE TRIGGER test_complete_commit_failure BEFORE UPDATE ON exploration_idempotency FOR EACH ROW BEGIN IF NEW.operation='COMPLETE' AND NEW.response_status IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic complete commit failure'; END IF; END");
        try {
            complete(id,rev,"retry",500);
            assertThat(count("exploration_result","session_id=?",id)).isZero();
            assertThat(count("exploration_result_seal","1=1")).isZero();
            assertThat(revision(id)).isEqualTo(rev);
        } finally {jdbc.execute("DROP TRIGGER test_complete_commit_failure");}
        complete(id,rev,"retry",200);
    }

    @Test void invalidCatalogCannotBeConvertedIntoNoCandidateResult() throws Exception {
        long id=ready(null,false,false);
        jdbc.update("UPDATE exploration_runtime_snapshot r JOIN exploration_session_pin p ON r.content_hash=p.snapshot_hash SET r.payload_json=JSON_OBJECT('changed',true) WHERE p.session_id=?",id);
        assertThat(complete(id,revisionSql(id),"corrupt",503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        assertThat(count("exploration_result","session_id=?",id)).isZero();
    }

    @Test void withdrawalPurgesResultAndSealAndOldJwtCannotAccessThem() throws Exception {
        long id=ready(null,false,false);JsonNode result=complete(id,revision(id),"complete",200);long rid=result.path("resultId").asLong();
        deletion.delete(OWNER);
        assertThat(count("exploration_result","id=?",rid)).isZero();assertThat(count("exploration_result_seal","result_id=?",rid)).isZero();
        resultById(rid,owner,401);
    }

    @Test void completionBodyIsStrictAndAbandonedSessionsCannotComplete() throws Exception {
        long id=start("start").path("sessionId").asLong();
        postJson(id+"/complete",owner,"invalid","{\"expectedRevision\":0,\"completionReason\":\"EARLY_EXIT\"}",400);
        postJson(id+"/complete",owner,"invalid","{\"expectedRevision\":0,\"result\":{}}",400);
        mvc.perform(post(API+"/"+id+"/complete").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(revisionBody(0))).andExpect(status().isBadRequest());
        postJson(id+"/abandon",owner,"abandon",revisionBody(0),200);
        assertThat(complete(id,1,"abandoned",409).path("code").asText()).isEqualTo("SESSION_ABANDONED");
    }

    @Test void semanticInputHashIgnoresDatabaseIdsAndCompletionTimestamps() throws Exception {
        long first=ready(null,false,false);JsonNode a=complete(first,revision(first),"first",200);String hash=stored(first).path("inputHash").asText();
        long second=ready(null,false,false);complete(second,revision(second),"second",200);
        assertThat(stored(second).path("inputHash").asText()).isEqualTo(hash);
        assertThat(first).isNotEqualTo(second);assertThat(a.path("completedAt").asText()).isNotBlank();
    }

    @Test void realCatalogProfileCanCompleteEarlyOnlyAfterItsEvidenceAndComparisonsAreSubmitted() throws Exception {
        long id=start("start").path("sessionId").asLong();
        var catalog=catalogs.load(id).branch();
        var engine=new QuestionBranchEngine(catalog);
        Map<String,Integer> recipe=null; boolean afterTwo=false;
        List<String> metrics=new ArrayList<>(catalog.scoring().calculator().aggregate(List.of()).interest().keySet());
        search:for(int baseline:List.of(0,25))for(String a:metrics)for(String b:metrics)if(!a.equals(b))for(int second:List.of(0,25,50,75)) {
            Map<String,Integer> target=new TreeMap<>();metrics.forEach(m->target.put(m,baseline));target.put(a,100);target.put(b,second);
            List<BranchCatalog.Key> history=new ArrayList<>();List<BranchCatalog.Answer> responses=new ArrayList<>();
            for(int n=0;n<3;n++) {
                var plan=engine.stageOne(responses,history).plan();
                if(plan.toIssue().isEmpty())break;
                for(var key:plan.toIssue()){history.add(key);responses.add(probeAnswer(catalog,key,target));}
            }
            var fixed=engine.freezeCandidates(responses,history);
            if(engine.canExitAtModuleBoundary(fixed,responses,history,QuestionBranchEngine.Presentation.none(),true)){recipe=target;break search;}
            var two=engine.stageTwo(fixed,responses,history,QuestionBranchEngine.Presentation.none());
            for(var key:two.toIssue()){history.add(key);responses.add(probeAnswer(catalog,key,target));}
            if(engine.canExitAtModuleBoundary(fixed,responses,history,QuestionBranchEngine.Presentation.none(),true)){recipe=target;afterTwo=true;break search;}
        }
        assertThat(recipe).as("actual current-catalog early-exit profile").isNotNull();
        int lastStage=afterTwo?2:1;
        for(int stage=1;stage<=lastStage;stage++) {
            for(int n=0;n<4;n++) {
                JsonNode next=issue(id);
                for(JsonNode q:next.path("issuedQuestions")) {
                    var source=catalog.question(q.path("questionId").asText());
                    var key=catalog.key(source,q.path("comparisonSet").isNull()?"":q.path("comparisonSet").asText());
                    String code=probeAnswer(catalog,key,recipe).optionCode();String mutation=UUID.randomUUID().toString();
                    response(put(API+"/"+id+"/answers/"+q.path("issuedId").asLong()).header("Authorization",owner).header("Idempotency-Key",mutation)
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedRevision",revision(id),"optionCode",code,"clientMutationId",mutation))),200);
                }
                if(getJson("/"+id,owner,200).path("nextAction").asText().equals("ADVANCE"))break;
            }
            if(stage<lastStage)postJson(id+"/advance",owner,"advance",revisionBody(revision(id)),200);
        }
        JsonNode result=complete(id,revision(id),"early",200);
        assertThat(result.path("completionReason").asText()).isEqualTo("EARLY_EXIT");
        assertThat(result.path("status").asText()).isEqualTo("READY");
        assertThat(getJson("/"+id,owner,200).path("session").path("stage").asInt()).isLessThan(3);
    }

    private BranchCatalog.Answer probeAnswer(BranchCatalog catalog,BranchCatalog.Key key,Map<String,Integer> recipe) {
        var q=catalog.question(key.questionId());int value=recipe.getOrDefault(q.metric(),75);
        var option=catalog.options(key).stream().filter(o->q.module().equals("STATE")?o.meaning().equals("STATE_BROAD"):Objects.equals(o.value(),value)).findFirst()
                .orElse(catalog.options(key).get(0));
        return new BranchCatalog.Answer(key,option.code(),true,true);
    }

    private long ready(Integer value,boolean inconsistent,boolean http) throws Exception {
        long id=start(UUID.randomUUID().toString()).path("sessionId").asLong();
        for(int stage=1;stage<=3;stage++) {
            for(int n=0;n<4;n++) {
                issueAndAnswer(id,value,inconsistent,http);
                String action=getJson("/"+id,owner,200).path("nextAction").asText();
                if(action.equals("ADVANCE")||action.equals("COMPLETE"))break;
            }
            if(stage<3)postJson(id+"/advance",owner,UUID.randomUUID().toString(),revisionBody(revision(id)),200);
        }
        return id;
    }
    private void issueAndAnswer(long id,Integer value,boolean inconsistent,boolean http) throws Exception {
        JsonNode next=issue(id);
        for(JsonNode q:next.path("issuedQuestions")) {
            long iid=q.path("issuedId").asLong();
            JsonNode snapshot=mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?",String.class,iid));
            String code=q.path("options").get(0).path("code").asText();
            Integer target=inconsistent&&snapshot.path("question").path("module_code").asText().equals("INTEREST_CONFIRM")?Integer.valueOf(100):value;
            for(JsonNode o:snapshot.path("options")) if(target==null ? o.path("is_unknown").asBoolean()||o.path("is_no_experience").asBoolean()
                    : o.path("numeric_value").asText().equals(target.toString())) {code=o.path("option_code").asText();break;}
            if(http) {
                String mutation=UUID.randomUUID().toString();
                response(put(API+"/"+id+"/answers/"+iid).header("Authorization",owner).header("Idempotency-Key",mutation).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision",revision(id),"optionCode",code,"clientMutationId",mutation))),200);
            } else {
                jdbc.update("INSERT INTO exploration_answer_revision(issued_id,answer_revision,option_code,is_current,client_mutation_id,mutation_request_hash,mutation_response_json,answered_at) VALUES (?,1,?,true,?,?,JSON_OBJECT(),NOW())",iid,code,UUID.randomUUID().toString(),new byte[32]);
                jdbc.update("UPDATE exploration_session SET revision=revision+1 WHERE id=?",id);
            }
        }
    }
    private JsonNode issue(long id)throws Exception{return postJson(id+"/next",owner,UUID.randomUUID().toString(),revisionBody(revision(id)),200);}
    private JsonNode complete(long id,long rev,String key,int status)throws Exception{return postJson(id+"/complete",owner,key,revisionBody(rev),status);}
    private String revisionBody(long rev)throws Exception{return mapper.writeValueAsString(Map.of("expectedRevision",rev));}
    private long revision(long id)throws Exception{return getJson("/"+id,owner,200).path("session").path("revision").asLong();}
    private long revisionSql(long id){return jdbc.queryForObject("SELECT revision FROM exploration_session WHERE id=?",Long.class,id);}
    private JsonNode resultById(long id,String token,int status)throws Exception{return response(get("/api/exploration/v1/results/"+id).header("Authorization",token),status);}
    private JsonNode stored(long id)throws Exception{return mapper.readTree(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE session_id=?",String.class,id));}
}
