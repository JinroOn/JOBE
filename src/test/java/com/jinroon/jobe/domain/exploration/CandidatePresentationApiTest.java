package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class CandidatePresentationApiTest extends ExplorationApiTestSupport {
    @Test void reactionsActivateConditionalQuestionsAndPersistWithoutChangingCandidatesOrScoreInputs() throws Exception {
        long id = stageTwo(false); JsonNode before = detail(id), fixed = before.path("fixedCandidates");
        String code = shown(before); String input = checkpoint(id).path("inputAnswers").toString();
        JsonNode saved = react(id, "reaction", revision(id), fixed.path("checkpointId").asLong(), List.of(code), List.of(code), 200);
        assertThat(saved.path("session").path("revision").asLong()).isEqualTo(before.path("session").path("revision").asLong()+1);
        assertThat(saved.path("fixedCandidates").path("majorCodes")).isEqualTo(fixed.path("majorCodes"));
        assertThat(saved.path("fixedCandidates").path("presentation").path("source").asText()).isEqualTo("USER_REPORTED");
        assertThat(checkpoint(id).path("inputAnswers").toString()).isEqualTo(input);
        assertThat(active(id)).contains("OPEN-03", "OPEN-04");
        assertThat(detail(id).path("fixedCandidates")).isEqualTo(saved.path("fixedCandidates"));
        assertThat(count("exploration_stage_checkpoint", "session_id=?", id)).isEqualTo(2);
        assertThat(count("exploration_issued_question", "session_id=? AND stage='STAGE_2'", id)).isZero();
    }

    @Test void replayRemainsExactAfterLaterWritesAndChangedBodyConflicts() throws Exception {
        long id = stageTwo(false); JsonNode before=detail(id); long rev=revision(id), cp=checkpointId(id); String code=shown(before);
        JsonNode saved=react(id,"same",rev,cp,List.of(code),List.of(),200);
        next(id);
        assertThat(react(id,"same",rev,cp,List.of(code),List.of(),200)).isEqualTo(saved);
        assertThat(react(id,"same",rev,cp,List.of(),List.of(),409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test void removingAndRestoringReactionPreservesIssuedIdentityAnswersAndSpentBudget() throws Exception {
        long id=stageTwo(false); String code=shown(detail(id));
        react(id,"on",revision(id),checkpointId(id),List.of(code),List.of(code),200);
        next(id); JsonNode old=detail(id); JsonNode q=findQuestion(old,"OPEN-04"); long iid=q.path("issuedId").asLong();
        answer(id,q,"ONE_EXTRA"); String snapshot=jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?",String.class,iid);
        int issued=count("exploration_issued_question","session_id=?",id);
        JsonNode off=react(id,"off",revision(id),checkpointId(id),List.of(),List.of(),200);
        assertThat(off.path("branchChanges").path("excludedIssuedIds").toString()).contains(Long.toString(iid));
        assertThat(count("exploration_issued_question","id=? AND effective=false",iid)).isEqualTo(1);
        assertThat(count("exploration_answer_revision","issued_id=? AND is_current=true",iid)).isEqualTo(1);
        JsonNode on=react(id,"on-again",revision(id),checkpointId(id),List.of(code),List.of(code),200);
        assertThat(on.path("branchChanges").path("restoredIssuedIds").toString()).contains(Long.toString(iid));
        assertThat(count("exploration_issued_question","session_id=?",id)).isEqualTo(issued);
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?",String.class,iid)).isEqualTo(snapshot);
        assertThat(findRow(detail(id),iid).path("answerCode").asText()).isEqualTo("ONE_EXTRA");
        assertThat(detail(id).path("progress").path("totalIssued").asInt()).isEqualTo(issued);
    }

    @Test void changingReactionInStageThreeRewindsAndExcludesLaterQuestions() throws Exception {
        long id=stageTwo(false); String code=shown(detail(id)); finish(id); advance(id); next(id);
        assertThat(detail(id).path("session").path("stage").asInt()).isEqualTo(3);
        JsonNode saved=react(id,"rewind",revision(id),checkpointId(id),List.of(code),List.of(code),200);
        assertThat(saved.path("rewound").asBoolean()).isTrue();
        assertThat(saved.path("session").path("stage").asInt()).isEqualTo(2);
        assertThat(count("exploration_stage_checkpoint","session_id=? AND stage='STAGE_3' AND valid=true",id)).isZero();
        assertThat(count("exploration_issued_question","session_id=? AND stage='STAGE_3' AND effective=true",id)).isZero();
        finish(id); advance(id); finish(id);
        assertThat(detail(id).path("progress").path("totalIssued").asInt()).isLessThanOrEqualTo(78);
    }

    @Test void editingUpstreamAnswersClearsReactionAndOldCheckpointCannotBeReused() throws Exception {
        long id=stageTwo(false); String code=shown(detail(id));
        react(id,"reaction",revision(id),checkpointId(id),List.of(code),List.of(code),200); long oldCp=checkpointId(id);
        JsonNode row=detail(id).path("issued").get(0); answer(id,row.path("question"),row.path("answerCode").asText());
        assertThat(detail(id).path("fixedCandidates").isNull()).isTrue(); finish(id); advance(id);
        assertThat(detail(id).path("fixedCandidates").path("presentation").path("source").asText()).isEqualTo("NOT_COLLECTED");
        assertThat(react(id,"old-cp",revision(id),oldCp,List.of(code),List.of(),409).path("code").asText()).isEqualTo("STALE_PRESENTATION");
    }

    @Test void explicitEmptyReactionIsCollectedAndNoCandidateCaseIsSupported() throws Exception {
        long id=stageTwo(true);
        assertThat(detail(id).path("fixedCandidates").path("shownCandidates")).isEmpty();
        JsonNode saved=react(id,"empty",revision(id),checkpointId(id),List.of(),List.of(),200);
        assertThat(saved.path("fixedCandidates").path("presentation").path("source").asText()).isEqualTo("USER_REPORTED");
        assertThat(active(id)).doesNotContain("OPEN-03","OPEN-04");
        assertThat(react(id,"invented",revision(id),checkpointId(id),List.of("M999"),List.of(),422).path("code").asText()).isEqualTo("MAJOR_NOT_PRESENTED");
    }

    @Test void oldCheckpointsRemainReadableAndCanCollectReactions() throws Exception {
        long id=stageTwo(false); JsonNode original=checkpoint(id);
        var old=original.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)old.path("presentation")).remove("shownMajorCodes");
        var json=new com.jinroon.jobe.domain.exploration.service.ExplorationJson(mapper);
        jdbc.update("UPDATE exploration_stage_checkpoint SET payload_json=?,payload_hash=? WHERE id=?",json.write(old),json.hex(old),checkpointId(id));
        String code=shown(detail(id)); react(id,"upgrade",revision(id),checkpointId(id),List.of(code),List.of(code),200);
        assertThat(active(id)).contains("OPEN-03","OPEN-04");
    }

    @Test void completedSnapshotFreezesReactionAndOpennessWithoutChangingCandidateRanks() throws Exception {
        long id=stageTwo(false); JsonNode preview=detail(id).path("fixedCandidates");
        List<String> codes=new ArrayList<>(); preview.path("presentation").path("shownMajorCodes").forEach(n->codes.add(n.asText()));
        react(id,"reported",revision(id),checkpointId(id),codes,codes,200); next(id);
        answer(id,findQuestion(detail(id),"OPEN-04"),"MAIN_ONLY");
        finish(id); advance(id); finish(id);
        JsonNode done=postJson(id+"/complete",owner,"complete",bodyRevision(id),200);
        assertThat(done.path("presentation").path("source").asText()).isEqualTo("USER_REPORTED");
        assertThat(done.path("presentation").path("unfamiliarRange").asText()).isEqualTo("OPEN_MAIN_ONLY");
        Set<String> reported=new HashSet<>(codes);
        for(JsonNode major:done.path("majors")) if(reported.contains(major.path("majorCode").asText())) {
            assertThat(major.path("collapsed").asBoolean()).isTrue();
            assertThat(major.path("collapseReasonCode").asText()).isEqualTo("OPENNESS_UNFAMILIAR_RANGE");
        }
        assertThat(react(id,"late",revision(id),checkpointId(id),List.of(),List.of(),409).path("code").asText()).isEqualTo("SESSION_ALREADY_COMPLETED");
        assertThat(postJson(id+"/complete",owner,"again",bodyRevision(id),200)).isEqualTo(done);
        assertThat(getJson("/"+id+"/result",owner,200).path("presentation")).isEqualTo(done.path("presentation"));
    }

    @Test void ownershipStatusAndRevisionChecksDoNotLeaveReservations() throws Exception {
        long id=stageTwo(false); long rev=revision(id),cp=checkpointId(id); String body=body(rev,cp,List.of(),List.of());
        request(id,other,"other",body,404); request(Long.MAX_VALUE,owner,"missing",body,404);
        assertThat(request(id,owner,"stale",body(rev-1,cp,List.of(),List.of()),409).path("code").asText()).isEqualTo("STALE_REVISION");
        postJson(id+"/abandon",owner,"abandon",bodyRevision(id),200);
        assertThat(request(id,owner,"late",body,409).path("code").asText()).isEqualTo("SESSION_ABANDONED");
        assertThat(count("exploration_idempotency","user_id=? AND operation='PRESENTATION'",OWNER)).isZero();
    }

    @Test void reactionBeforeStageTwoIsRejected() throws Exception {
        long id=start("start").path("sessionId").asLong();
        assertThat(react(id,"early",revision(id),1,List.of(),List.of(),409).path("code").asText()).isEqualTo("CANDIDATES_NOT_PRESENTED");
    }

    @Test void strictJsonHeaderAuthenticationAndQueryValidation() throws Exception {
        long id=stageTwo(false); long rev=revision(id),cp=checkpointId(id); String code=shown(detail(id));
        String valid=body(rev,cp,List.of(code),List.of());
        response(put(API+"/"+id+"/presentation").contentType(MediaType.APPLICATION_JSON).content(valid),401);
        response(put(API+"/"+id+"/presentation").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(valid),400);
        for(String invalid:List.of("{}",valid.replace("\"expectedRevision\":"+rev,"\"expectedRevision\":1.5"),
                valid.replace("\"checkpointId\":"+cp,"\"checkpointId\":true"),valid.replace("\"unfamiliar\":[]","\"unfamiliar\":null"),
                valid.replace("\"unfamiliar\":[]","\"unfamiliar\":[1]"),valid.replace("\"unfamiliar\":[]","\"unfamiliar\":[\"x\",\"x\"]"),
                valid.substring(0,valid.length()-1)+",\"source\":\"USER_REPORTED\"}")) request(id,owner,UUID.randomUUID().toString(),invalid,400);
        response(put(API+"/"+id+"/presentation?score=100").header("Authorization",owner).header("Idempotency-Key","query").contentType(MediaType.APPLICATION_JSON).content(valid),400);
    }

    @Test void checkpointWriteFailureRollsBackReactionRevisionAndCanRetrySameKey() throws Exception {
        long id=stageTwo(false); long rev=revision(id),cp=checkpointId(id); String code=shown(detail(id));
        jdbc.execute("CREATE TRIGGER test_reaction_failure BEFORE INSERT ON exploration_stage_checkpoint FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic reaction failure'");
        try {
            react(id,"retry",rev,cp,List.of(code),List.of(code),500);
            assertThat(revision(id)).isEqualTo(rev); assertThat(checkpointId(id)).isEqualTo(cp);
            assertThat(count("exploration_idempotency","user_id=? AND operation='PRESENTATION'",OWNER)).isZero();
        } finally { jdbc.execute("DROP TRIGGER test_reaction_failure"); }
        react(id,"retry",rev,cp,List.of(code),List.of(code),200);
    }

    @Test void concurrentIdenticalRequestsReplayOneDecision() throws Exception {
        long id=stageTwo(false); long rev=revision(id),cp=checkpointId(id); String code=shown(detail(id));
        var replies=parallel(()->service.presentation(OWNER,id,"same",rev,cp,List.of(code),List.of(code)));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_stage_checkpoint","session_id=?",id)).isEqualTo(2);
    }

    @Test void concurrentDifferentRequestsHaveOneStaleConflict() throws Exception {
        long id=stageTwo(false); long rev=revision(id),cp=checkpointId(id);
        var replies=parallel(()->{try{service.presentation(OWNER,id,UUID.randomUUID().toString(),rev,cp,List.of(),List.of());return "SUCCESS";}
            catch(ExplorationApiException e){return e.getCode();}});
        assertThat(replies).containsExactlyInAnyOrder("SUCCESS","STALE_REVISION");
    }

    @Test void corruptedCheckpointFailsClosedAndWithdrawalErasesHistoricalReactions() throws Exception {
        long id=stageTwo(false); String code=shown(detail(id)); react(id,"saved",revision(id),checkpointId(id),List.of(code),List.of(code),200);
        long rev=revision(id),cp=checkpointId(id);
        jdbc.update("UPDATE exploration_stage_checkpoint SET payload_json=JSON_SET(payload_json,'$.presentation.unfamiliar',JSON_ARRAY('FORGED')) WHERE id=?",cp);
        react(id,"corrupt",rev,cp,List.of(),List.of(),503); deletion.delete(OWNER);
        assertThat(count("exploration_stage_checkpoint","session_id=?",id)).isZero(); getJson("/"+id,owner,401);
    }

    private long stageTwo(boolean unknown) throws Exception {
        long id=start("start").path("sessionId").asLong(); finish(id,unknown); advance(id); return id;
    }
    private JsonNode detail(long id)throws Exception{return getJson("/"+id,owner,200);}
    private long revision(long id){return jdbc.queryForObject("SELECT revision FROM exploration_session WHERE id=?",Long.class,id);}
    private long checkpointId(long id){return jdbc.queryForObject("SELECT id FROM exploration_stage_checkpoint WHERE session_id=? AND stage='STAGE_2' AND valid=true",Long.class,id);}
    private JsonNode checkpoint(long id)throws Exception{return mapper.readTree(jdbc.queryForObject("SELECT payload_json FROM exploration_stage_checkpoint WHERE id=?",String.class,checkpointId(id)));}
    private List<String> active(long id)throws Exception{List<String> r=new ArrayList<>();checkpoint(id).path("plan").path("active").forEach(n->r.add(n.path("questionId").asText()));return r;}
    private String shown(JsonNode detail){return detail.path("fixedCandidates").path("shownCandidates").get(0).path("majorCode").asText();}
    private String bodyRevision(long id)throws Exception{return mapper.writeValueAsString(Map.of("expectedRevision",revision(id)));}
    private String body(long rev,long cp,List<String> u,List<String> f)throws Exception{return mapper.writeValueAsString(Map.of("expectedRevision",rev,"checkpointId",cp,"unexpected",u,"unfamiliar",f));}
    private JsonNode react(long id,String key,long rev,long cp,List<String> u,List<String> f,int status)throws Exception{return request(id,owner,key,body(rev,cp,u,f),status);}
    private JsonNode request(long id,String token,String key,String body,int status)throws Exception{return response(put(API+"/"+id+"/presentation").header("Authorization",token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body),status);}
    private JsonNode next(long id)throws Exception{return postJson(id+"/next",owner,UUID.randomUUID().toString(),bodyRevision(id),200);}
    private void advance(long id)throws Exception{postJson(id+"/advance",owner,UUID.randomUUID().toString(),bodyRevision(id),200);}
    private void answer(long id,JsonNode q,String code)throws Exception{String key=UUID.randomUUID().toString();response(put(API+"/"+id+"/answers/"+q.path("issuedId").asLong()).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedRevision",revision(id),"optionCode",code,"clientMutationId",key))),200);}
    private JsonNode findQuestion(JsonNode detail,String name){for(JsonNode row:detail.path("issued"))if(row.path("question").path("questionId").asText().equals(name))return row.path("question");throw new AssertionError(name);}
    private JsonNode findRow(JsonNode detail,long iid){for(JsonNode row:detail.path("issued"))if(row.path("question").path("issuedId").asLong()==iid)return row;throw new AssertionError(iid);}
    private void finish(long id)throws Exception{finish(id,false);}
    private void finish(long id,boolean unknown)throws Exception{
        for(int n=0;n<5;n++){
            for(JsonNode q:next(id).path("issuedQuestions")){
                String code=q.path("options").get(0).path("code").asText();
                if(unknown){JsonNode snapshot=mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?",String.class,q.path("issuedId").asLong()));
                    for(JsonNode o:snapshot.path("options"))if(o.path("is_unknown").asBoolean()||o.path("is_no_experience").asBoolean()){code=o.path("option_code").asText();break;}}
                answer(id,q,code);
            }
            if(Set.of("ADVANCE","COMPLETE").contains(detail(id).path("nextAction").asText()))return;
        }
        throw new AssertionError("stage not finished");
    }
}
