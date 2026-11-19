package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.jinroon.jobe.domain.exploration.consultation.ExplorationConsultationService;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiInputAdapter;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ExplorationConsultationStartApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationConsultationService consultations;
    @Autowired ResultContextService contexts;
    @Autowired PostResultSupportService support;
    @Autowired ExplorationAiInputAdapter adapter;
    @Autowired ExplorationJson json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String path(long result){return "/api/exploration/v1/results/"+result+"/consultations";}
    @Test void opensAndResumesOneEmptyConversationWithoutGeneratingOrTouchingLegacy() throws Exception {
        long result=completedResult(true);long legacy=count("consultation_sessions","user_id=?",OWNER);
        var first=response(post(path(result)).header("Authorization",owner).header("Idempotency-Key","open").contentType(MediaType.APPLICATION_JSON).content("{}"),200);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("consultationId","resultId","messages");
        assertThat(first.path("messages").isEmpty()).isTrue();assertThat(first.path("resultId").asLong()).isEqualTo(result);
        for(String key:List.of("open","another"))assertThat(response(post(path(result)).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{}"),200)).isEqualTo(first);
        assertThat(count("exploration_consultation","result_id=?",result)).isEqualTo(1);
        assertThat(count("consultation_sessions","user_id=?",OWNER)).isEqualTo(legacy);
        assertThat(count("exploration_generation_task","user_id=?",OWNER)).isZero();
        assertThat(jdbc.queryForObject("SELECT target_major_code FROM exploration_result_context WHERE result_id=? AND context_type='CONSULTATION'",String.class,result)).isNull();
    }

    @Test void newConversationPinsTheSelectedSupportTargetAndResumesWithoutChangingIt() throws Exception {
        long result = completedResult(true);
        var candidate = resultSnapshot(result).path("majorSummaries").get(0);
        String target = candidate.path("majorCode").asText();
        String name = candidate.path("majorName").asText();
        assertThat(name).isNotBlank();
        support.select(OWNER, result, "select-target", 0, "BOTH", target);

        var opened = response(post(path(result)).header("Authorization",owner).header("Idempotency-Key","open-target")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
        long context = jdbc.queryForObject("SELECT context_id FROM exploration_consultation WHERE id=?",
                Long.class, opened.path("consultationId").asLong());
        var before = contexts.readForAdapter(OWNER, context, "CONSULTATION").snapshot();
        var conversation = mapper.createObjectNode().put("question", "SYNTHETIC QUESTION");
        conversation.putArray("history");
        var modelRequest = adapter.buildConversation(before, "SYNTHETIC_TURN", conversation);
        assertThat(before.path("targetMajorCode").asText()).isEqualTo(target);
        assertThat(modelRequest.path("input").path("targetName").asText()).isEqualTo(name);

        support.select(OWNER, result, "clear-target", 1, "ACTIVITY", null);
        var resumed = response(post(path(result)).header("Authorization",owner).header("Idempotency-Key","resume-target")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200);
        assertThat(resumed).isEqualTo(opened);
        assertThat(contexts.readForAdapter(OWNER, context, "CONSULTATION").snapshot()).isEqualTo(before);
    }
    @Test void concurrentOpensReturnSameConversation() throws Exception {
        long result=completedResult(true);
        var answers=parallel(()->consultations.open(OWNER,result,UUID.randomUUID().toString()).consultationId());
        assertThat(answers.get(0)).isEqualTo(answers.get(1));assertThat(count("exploration_consultation","result_id=?",result)).isEqualTo(1);
    }
    @Test void noCandidateResultCanOpenAndSupportChangesDoNotRewriteFrozenContext() throws Exception {
        long result=completedResult(false);var first=consultations.open(OWNER,result,"one");
        long context=jdbc.queryForObject("SELECT context_id FROM exploration_consultation WHERE id=?",Long.class,first.consultationId());
        String before=jdbc.queryForObject("SELECT snapshot_json FROM exploration_result_context WHERE id=?",String.class,context);
        support.select(OWNER,result,"support",0,"ACTIVITY",null);
        assertThat(consultations.open(OWNER,result,"two")).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM exploration_result_context WHERE id=?",String.class,context)).isEqualTo(before);
    }
    @Test void refusesForeignMissingUnauthenticatedAndUnexpectedInputs() throws Exception {
        long result=completedResult(true);
        response(post(path(result)).header("Authorization",other).header("Idempotency-Key","open").contentType(MediaType.APPLICATION_JSON).content("{}"),404);
        response(post(path(Long.MAX_VALUE)).header("Authorization",owner).header("Idempotency-Key","open").contentType(MediaType.APPLICATION_JSON).content("{}"),404);
        response(post(path(result)).header("Idempotency-Key","open").contentType(MediaType.APPLICATION_JSON).content("{}"),401);
        for(String body:List.of("null","[]","{\"userId\":1}","{\"contextId\":1}"))response(post(path(result)).header("Authorization",owner).header("Idempotency-Key","open").contentType(MediaType.APPLICATION_JSON).content(body),400);
        response(post(path(result)+"?mode=MODEL").header("Authorization",owner).header("Idempotency-Key","open").contentType(MediaType.APPLICATION_JSON).content("{}"),400);
        response(post(path(result)).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"),400);
        assertThat(count("exploration_consultation","result_id=?",result)).isZero();
    }
    long seedTurn(long result,long consultation) {
        long context=jdbc.queryForObject("SELECT context_id FROM exploration_consultation WHERE id=?",Long.class,consultation);
        var conversation=mapper.createObjectNode().put("question","SYNTHETIC QUESTION");conversation.putArray("history");
        var request=adapter.buildConversation(contexts.readForAdapter(OWNER,context,"CONSULTATION").snapshot(),"SYNTHETIC_TURN",conversation);
        jdbc.update("INSERT INTO exploration_consultation_turn(consultation_id,turn_no,mutation_hash,generation_mutation,request_json,request_hash) VALUES (?,1,?,?,?,?)",consultation,new byte[32],UUID.randomUUID().toString(),request.toString(),json.hash(request));
        return jdbc.queryForObject("SELECT id FROM exploration_consultation_turn WHERE consultation_id=?",Long.class,consultation);
    }
    @Test void withdrawalCascadesNewConversationTurnAndMessage() throws Exception {
        long result=completedResult(true);long consultation=consultations.open(OWNER,result,"open").consultationId();long turn=seedTurn(result,consultation);
        jdbc.update("INSERT INTO exploration_consultation_message(turn_id,role,content,sources_json,evidence_status,message_hash) VALUES (?,'user','SYNTHETIC QUESTION',JSON_ARRAY(),NULL,?)",turn,new byte[32]);
        deletion.delete(OWNER);
        assertThat(count("exploration_consultation","id=?",consultation)).isZero();
        assertThat(count("exploration_consultation_turn","id=?",turn)).isZero();
        assertThat(count("exploration_consultation_message","turn_id=?",turn)).isZero();
    }
    @Test void databaseRejectsReparentingTurnRewriteDuplicateRolesAndUngroundedSources() throws Exception {
        long result=completedResult(true);long c=consultations.open(OWNER,result,"open").consultationId();long turn=seedTurn(result,c);
        assertThatThrownBy(()->jdbc.update("UPDATE exploration_consultation SET context_id=context_id+1 WHERE id=?",c)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE exploration_consultation_turn SET request_json=JSON_OBJECT() WHERE id=?",turn)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("INSERT INTO exploration_consultation_message(turn_id,role,content,sources_json,evidence_status,message_hash) VALUES (?,'user','SYNTHETIC QUESTION',JSON_ARRAY(),NULL,?)",turn,new byte[32]);
        assertThatThrownBy(()->jdbc.update("INSERT INTO exploration_consultation_message(turn_id,role,content,sources_json,evidence_status,message_hash) VALUES (?,'user','duplicate',JSON_ARRAY(),NULL,?)",turn,new byte[32])).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO exploration_consultation_message(turn_id,role,content,sources_json,evidence_status,message_hash) VALUES (?,'assistant','unsupported',JSON_ARRAY(),'GROUNDED',?)",turn,new byte[32])).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void failedOuterTransactionLeavesNoConversationOrPreparedContext() throws Exception {
        long result=completedResult(true);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{consultations.open(OWNER,result,"rollback");throw new IllegalStateException("SYNTHETIC rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count("exploration_consultation","result_id=?",result)).isZero();
        assertThat(count("exploration_result_context","result_id=?",result)).isZero();
        assertThat(consultations.open(OWNER,result,"retry").resultId()).isEqualTo(result);
    }
    @Test void corruptedExistingContextCannotBeReopenedAsAnEmptyHealthyConversation() throws Exception {
        long result=completedResult(true);var c=consultations.open(OWNER,result,"open");
        long context=jdbc.queryForObject("SELECT context_id FROM exploration_consultation WHERE id=?",Long.class,c.consultationId());
        jdbc.update("UPDATE exploration_result_context SET input_hash=? WHERE id=?",new byte[32],context);
        var error=response(post(path(result)).header("Authorization",owner).header("Idempotency-Key","reopen").contentType(MediaType.APPLICATION_JSON).content("{}"),503);
        assertThat(error.path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
    }
    @Test void resultAndContextCannotBeCrossWiredAtDatabaseLevel() throws Exception {
        long a=completedResult(true),b=completedResult(true);
        var c=consultations.open(OWNER,a,"open");long context=jdbc.queryForObject("SELECT context_id FROM exploration_consultation WHERE id=?",Long.class,c.consultationId());
        assertThatThrownBy(()->jdbc.update("INSERT INTO exploration_consultation(result_id,context_id) VALUES (?,?)",b,context)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
