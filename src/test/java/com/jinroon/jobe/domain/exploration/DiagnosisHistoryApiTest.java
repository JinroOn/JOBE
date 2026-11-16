package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultAssembler;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import com.jinroon.jobe.domain.exploration.history.HistoryCursorCodec;
import com.jinroon.jobe.domain.exploration.history.HistoryQuery;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DiagnosisHistoryApiTest extends ExplorationApiTestSupport {
    private static final String HISTORY="/api/exploration/v1/history";
    private static final LocalDateTime SAME=LocalDateTime.of(2026,11,15,12,0,0);
    @Autowired DiagnosisSessionRepository legacySessions;
    @Autowired DiagnosisResultRepository legacyResults;
    @Autowired ExplorationResultStore resultStore;
    @Autowired HistoryCursorCodec cursors;

    @Test void emptyHistoryIsAStableEmptyPage() throws Exception {
        for(String view:List.of("sessions","results")) {
            JsonNode page=page(view,owner,null,null,null,null,200);
            assertThat(page.path("items").isEmpty()).isTrue();assertThat(page.path("hasMore").asBoolean()).isFalse();
            assertThat(page.path("nextCursor").isNull()).isTrue();assertThat(page.path("limit").asInt()).isEqualTo(20);
        }
    }

    @Test void sameNumericSessionIdsRemainDistinctAndLinkToCorrectExistingApis() throws Exception {
        long exploration=start("start").path("sessionId").asLong();
        long legacy=legacy(OWNER,"in_progress",SAME);
        jdbc.update("UPDATE diagnosis_sessions SET id=? WHERE id=?",exploration,legacy);
        jdbc.update("UPDATE exploration_session SET started_at=? WHERE id=?",SAME,exploration);
        JsonNode rows=page("sessions",owner,null,null,null,null,200).path("items");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).path("key").asText()).isEqualTo("EXPLORATION_V1:SESSION:"+exploration);
        assertThat(rows.get(1).path("key").asText()).isEqualTo("LEGACY:SESSION:"+exploration);
        assertThat(rows.get(0).path("links").path("resume").asText()).isEqualTo(API+"/"+exploration);
        assertThat(rows.get(1).path("links").path("resume").asText()).isEqualTo("/api/diagnoses/sessions/"+exploration);
        response(get(rows.get(0).path("links").path("session").asText()).header("Authorization",owner),200);
        response(get(rows.get(1).path("links").path("session").asText()).header("Authorization",owner),200);
        assertThat(rows.toString()).doesNotContain("userId","inputSnapshot","competencyVector","shareToken");
    }

    @Test void ownershipFiltersApplyToBothSessionAndResultOwners() throws Exception {
        long owned=legacy(OWNER,"completed",SAME),foreign=legacy(OTHER,"completed",SAME);
        legacyResult(owned,OTHER); // wrong result owner must not be linked from this user's session
        legacyResult(foreign,OWNER); // wrong session owner must not be listed as this user's result
        long mine=legacy(OWNER,"completed",SAME.minusDays(1));long rid=legacyResult(mine,OWNER);
        JsonNode sessions=page("sessions",owner,null,null,null,null,200).path("items");
        assertThat(sessions.size()).isEqualTo(2);
        assertThat(find(sessions,owned).path("resultAvailability").asText()).isEqualTo("MISSING");
        JsonNode resultRows=page("results",owner,null,null,null,null,200).path("items");
        assertThat(resultRows.size()).isEqualTo(1);assertThat(resultRows.get(0).path("resultId").asLong()).isEqualTo(rid);
        assertThat(page("sessions",other,null,null,null,null,200).path("items").size()).isEqualTo(1);
    }

    @Test void typeAndStatusFiltersPreserveAbandonedWithoutOfferingResume() throws Exception {
        legacy(OWNER,"in_progress",SAME);legacy(OWNER,"completed",SAME);legacy(OWNER,"abandoned",SAME);
        long exp=start("start").path("sessionId").asLong();
        assertThat(page("sessions",owner,"LEGACY",null,null,null,200).path("items").size()).isEqualTo(3);
        assertThat(page("sessions",owner,"EXPLORATION_V1",null,null,null,200).path("items").size()).isEqualTo(1);
        JsonNode abandoned=page("sessions",owner,null,"abandoned",null,null,200).path("items").get(0);
        assertThat(abandoned.path("nextAction").asText()).isEqualTo("NONE");assertThat(abandoned.path("links").path("resume").isNull()).isTrue();
        postJson(exp+"/abandon",owner,"abandon","{\"expectedRevision\":0}",200);
        assertThat(page("sessions",owner,null,"in_progress",null,null,200).path("items").size()).isEqualTo(1);
    }

    @Test void tiesUseKeysetOrderingAcrossSourcesWithoutDuplicatesOrGaps() throws Exception {
        for(int n=0;n<5;n++)legacy(OWNER,"in_progress",SAME);
        for(int n=0;n<3;n++) {long id=start("start-"+n).path("sessionId").asLong();jdbc.update("UPDATE exploration_session SET started_at=? WHERE id=?",SAME,id);postJson(id+"/abandon",owner,"abandon-"+n,"{\"expectedRevision\":0}",200);}
        List<String> all=new ArrayList<>();String cursor=null;
        do {
            JsonNode page=page("sessions",owner,null,null,2,cursor,200);
            page.path("items").forEach(x->all.add(x.path("key").asText()));
            cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asText();
        } while(cursor!=null);
        assertThat(all).hasSize(8).doesNotHaveDuplicates();
        assertThat(all.subList(0,3)).allMatch(k->k.startsWith("EXPLORATION_V1:"));
        assertThat(all.subList(3,8)).allMatch(k->k.startsWith("LEGACY:"));
    }

    @Test void cursorFencesExcludeNewAndBackdatedInsertionsUntilARefresh() throws Exception {
        for(int n=0;n<4;n++)legacy(OWNER,"in_progress",SAME.minusDays(n));
        JsonNode first=page("sessions",owner,null,null,2,null,200);
        long newest=legacy(OWNER,"in_progress",SAME.plusDays(1)),backdated=legacy(OWNER,"in_progress",SAME.minusDays(2));
        JsonNode second=page("sessions",owner,null,null,2,first.path("nextCursor").asText(),200);
        assertThat(second.path("items").size()).isEqualTo(2);
        for(JsonNode item:second.path("items"))assertThat(item.path("sessionId").asLong()).isNotIn(newest,backdated);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(page("sessions",owner,null,null,100,null,200).path("items").size()).isEqualTo(6);
    }

    @Test void deletedCursorAnchorDoesNotBreakTheNextPage() throws Exception {
        for(int n=0;n<4;n++)legacy(OWNER,"in_progress",SAME.minusDays(n));
        JsonNode first=page("sessions",owner,null,null,2,null,200);
        long anchor=first.path("items").get(1).path("sessionId").asLong();legacySessions.deleteById(anchor);
        JsonNode second=page("sessions",owner,null,null,2,first.path("nextCursor").asText(),200);
        assertThat(second.path("items").size()).isEqualTo(2);assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    @Test void cursorsRejectTamperingAndCrossUserViewOrFilterReuse() throws Exception {
        for(int n=0;n<3;n++)legacy(OWNER,"completed",SAME);
        String cursor=page("sessions",owner,null,null,1,null,200).path("nextCursor").asText();
        assertThat(page("sessions",other,null,null,1,cursor,400).path("code").asText()).isEqualTo("INVALID_CURSOR");
        page("results",owner,null,null,1,cursor,400);page("sessions",owner,"LEGACY",null,1,cursor,400);
        page("sessions",owner,null,"completed",1,cursor,400);page("sessions",owner,null,null,2,cursor,400);
        String changed=(cursor.charAt(0)=='A'?'B':'A')+cursor.substring(1);page("sessions",owner,null,null,1,changed,400);
        for(String invalid:List.of("","not-a-cursor","a.b","a".repeat(2100)))page("sessions",owner,null,null,1,invalid,400);
    }

    @Test void invalidQueryTypesLimitsDuplicatesAndOwnerInjectionAreRejected() throws Exception {
        for(String query:List.of("type=legacy","type=INVALID","status=unknown","limit=0","limit=-1","limit=101","limit=1.5","limit=abc","limit=01","limit=99999999999999999","userId=8200000002","type=ALL&type=LEGACY","limit=1&limit=2","unexpected=1"))
            response(get(HISTORY+"/sessions?"+query).header("Authorization",owner),400);
        page("results",owner,null,"in_progress",null,null,400);
        page("results",owner,null,"abandoned",null,null,400);
        response(get(HISTORY+"/sessions"),401);
        page("sessions",outside,null,null,null,null,503);
    }

    @Test void missingAndDuplicateLegacyResultsAreMarkedWithoutRewritingOrPickingOne() throws Exception {
        long missing=legacy(OWNER,"completed",SAME),multiple=legacy(OWNER,"completed",SAME.minusDays(1));
        legacyResult(multiple,OWNER);legacyResult(multiple,OWNER);
        JsonNode rows=page("sessions",owner,"LEGACY",null,null,null,200).path("items");
        assertThat(find(rows,missing).path("resultAvailability").asText()).isEqualTo("MISSING");
        JsonNode ambiguous=find(rows,multiple);
        assertThat(ambiguous.path("resultAvailability").asText()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.path("resultId").isNull()).isTrue();assertThat(ambiguous.path("nextAction").asText()).isEqualTo("NONE");
        assertThat(page("results",owner,"LEGACY",null,null,null,200).path("items").size()).isEqualTo(2);
        assertThat(count("diagnosis_results","diagnosis_session_id=?",multiple)).isEqualTo(2);
    }

    @Test void sameNumericResultIdsUseDistinctTypeKeysAndResultRoutes() throws Exception {
        long session=start("start").path("sessionId").asLong();long rid=explorationResult(session);
        long legacy=legacy(OWNER,"completed",SAME);long legacyId=legacyResult(legacy,OWNER);
        jdbc.update("UPDATE diagnosis_results SET id=? WHERE id=?",rid,legacyId);
        JsonNode rows=page("results",owner,null,null,null,null,200).path("items");assertThat(rows.size()).isEqualTo(2);
        Set<String> keys=new HashSet<>();
        for(JsonNode row:rows) {
            keys.add(row.path("resultKey").asText());assertThat(row.path("resultId").asLong()).isEqualTo(rid);
            response(get(row.path("links").path("result").asText()).header("Authorization",owner),200);
            assertThat(row.path("links").path("resume").isNull()).isTrue();
        }
        assertThat(keys).containsExactlyInAnyOrder("EXPLORATION_V1:RESULT:"+rid,"LEGACY:RESULT:"+rid);
        assertThat(rows.toString()).doesNotContain("competencyVector","tendencyVector","shareToken","majorSummaries","userId","inputHash");
    }

    @Test void resultPaginationSortsByCompletionAndExcludesLaterCompletionsUntilRefresh() throws Exception {
        for(int n=0;n<4;n++){long id=legacy(OWNER,"completed",SAME.minusDays(n));legacyResult(id,OWNER);}
        JsonNode first=page("results",owner,null,null,2,null,200);assertThat(first.path("items").get(0).path("occurredAt").asText()).startsWith("2026-11-15T12:00");
        long later=legacy(OWNER,"completed",SAME.minusDays(2));long rid=legacyResult(later,OWNER);
        JsonNode second=page("results",owner,null,null,2,first.path("nextCursor").asText(),200);
        assertThat(second.path("items").size()).isEqualTo(2);
        for(JsonNode row:second.path("items"))assertThat(row.path("resultId").asLong()).isNotEqualTo(rid);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    @Test void incompleteExplorationDoesNotAppearAsACompletedResultAndListsStayReadOnly() throws Exception {
        long id=start("start").path("sessionId").asLong();
        for(int n=0;n<2;n++){page("sessions",owner,null,null,null,null,200);page("results",owner,null,null,null,null,200);}
        assertThat(page("results",owner,null,null,null,null,200).path("items").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision FROM exploration_session WHERE id=?",Long.class,id)).isZero();
        assertThat(count("exploration_issued_question","session_id=?",id)).isZero();
        assertThat(count("exploration_stage_checkpoint","session_id=?",id)).isZero();
        assertThat(count("exploration_result","session_id=?",id)).isZero();
    }

    @Test void corruptedResultIsIsolatedWithoutLeakingOrRepairingItsContent() throws Exception {
        long id=start("start").path("sessionId").asLong();long rid=explorationResult(id);
        long healthy=start("healthy").path("sessionId").asLong();long healthyRid=explorationResult(healthy);
        long foreign=postJson("",other,"foreign",START,201).path("sessionId").asLong();explorationResult(foreign);
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE id=?",rid);
        String corrupted=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        String seal=jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?",String.class,rid);
        for(String view:List.of("sessions","results")) {
            JsonNode items=page(view,owner,null,null,null,null,200).path("items");
            assertThat(items.size()).isEqualTo(2);
            JsonNode bad=find(items,id),good=find(items,healthy);
            assertThat(bad.path("resultAvailability").asText()).isEqualTo("UNAVAILABLE");
            assertThat(bad.path("resultUnavailableReason").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
            for(String field:List.of("resultId","resultKey","resultStatus","resultContractVersion")) assertThat(bad.path(field).isNull()).isTrue();
            assertThat(bad.path("links").path("result").isNull()).isTrue();assertThat(bad.path("links").path("resume").isNull()).isTrue();
            assertThat(bad.path("nextAction").asText()).isEqualTo("NONE");
            assertThat(good.path("resultAvailability").asText()).isEqualTo("AVAILABLE");assertThat(good.path("resultId").asLong()).isEqualTo(healthyRid);
            assertThat(page(view,other,null,null,null,null,200).path("items").size()).isEqualTo(1);
            JsonNode first=page(view,owner,null,null,1,null,200);
            JsonNode second=page(view,owner,null,null,1,first.path("nextCursor").asText(),200);
            assertThat(first.path("hasMore").asBoolean()).isTrue();assertThat(second.path("hasMore").asBoolean()).isFalse();
            assertThat(first.path("items").get(0).path("sessionId").asLong()).isNotEqualTo(second.path("items").get(0).path("sessionId").asLong());
        }
        JsonNode sessions=getJson("",owner,200);
        assertThat(find(sessions,id).path("resultAvailability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(find(sessions,id).path("resultId").isNull()).isTrue();
        assertThat(find(sessions,healthy).path("resultId").asLong()).isEqualTo(healthyRid);
        getJson("/"+id+"/result",owner,503);
        response(get("/api/exploration/v1/results/"+rid).header("Authorization",owner),503);
        response(get("/api/exploration/v1/results/"+rid).header("Authorization",other),404);
        response(get("/api/exploration/v1/results/"+healthyRid).header("Authorization",owner),200);
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(corrupted);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?",String.class,rid)).isEqualTo(seal);
    }

    @Test void missingResultIsMarkedInSessionsAndDoesNotBlockOtherResultHistory() throws Exception {
        long id=start("start").path("sessionId").asLong();long rid=explorationResult(id);
        long healthy=start("healthy").path("sessionId").asLong();long healthyRid=explorationResult(healthy);
        jdbc.update("DELETE FROM exploration_result WHERE id=?",rid);
        assertThat(find(page("sessions",owner,null,null,null,null,200).path("items"),id).path("resultAvailability").asText()).isEqualTo("MISSING");
        assertThat(find(getJson("",owner,200),id).path("resultAvailability").asText()).isEqualTo("MISSING");
        JsonNode results=page("results",owner,null,null,null,null,200).path("items");
        assertThat(results.size()).isEqualTo(1);assertThat(results.get(0).path("resultId").asLong()).isEqualTo(healthyRid);
        getJson("/"+id+"/result",owner,503);
        legacy(OWNER,"in_progress",SAME);
        assertThat(page("sessions",owner,"LEGACY",null,null,null,200).path("items").size()).isEqualTo(1);
    }

    @Test void existingArrayListsRemainCompatibleAndLegacyResultContentIsUnchanged() throws Exception {
        long id=legacy(OWNER,"completed",SAME);long rid=legacyResult(id,OWNER);
        String before=jdbc.queryForObject("SELECT competency_vector FROM diagnosis_results WHERE id=?",String.class,rid);
        long exp=start("start").path("sessionId").asLong();
        assertThat(getJson("",owner,200).isArray()).isTrue();
        assertThat(response(get("/api/diagnoses/sessions/me").header("Authorization",owner),200).isArray()).isTrue();
        assertThat(response(get("/api/results").header("Authorization",owner),200).isArray()).isTrue();
        page("sessions",owner,null,null,null,null,200);page("results",owner,null,null,null,null,200);
        assertThat(jdbc.queryForObject("SELECT competency_vector FROM diagnosis_results WHERE id=?",String.class,rid)).isEqualTo(before);
        assertThat(count("exploration_result","session_id=?",exp)).isZero();
    }

    @Test void withdrawalPurgesHistoryAndOldJwtCannotContinueWithItsCursor() throws Exception {
        legacy(OWNER,"in_progress",SAME);legacy(OWNER,"in_progress",SAME.minusDays(1));
        String cursor=page("sessions",owner,null,null,1,null,200).path("nextCursor").asText();
        deletion.delete(OWNER);page("sessions",owner,null,null,1,cursor,401);
        assertThat(page("sessions",other,null,null,null,null,200).path("items").isEmpty()).isTrue();
    }

    private long legacy(long owner,String status,LocalDateTime at) {
        long id=legacySessions.saveAndFlush(DiagnosisSession.start(owner,2,"{\"private\":true}")).getId();
        jdbc.update("UPDATE diagnosis_sessions SET status=?,started_at=?,completed_at=? WHERE id=?",status,at,status.equals("completed")?at:null,id);
        return id;
    }
    private long legacyResult(long session,long owner) {
        return legacyResults.saveAndFlush(DiagnosisResult.create(session,owner,"{\"privateScore\":87}","{}","[]",UUID.randomUUID().toString())).getId();
    }
    private long explorationResult(long session) {
        // Minimal sealed fixture to test history projection. Full completion behavior has separate HTTP tests.
        ObjectNode payload=mapper.createObjectNode();payload.put("sessionId",session);payload.put("resultContractVersion",ExplorationResultAssembler.CONTRACT);
        payload.put("sessionStatus","completed");payload.put("status","NO_SUPPORTED_CANDIDATE");payload.put("completionReason","NORMAL");
        ObjectNode input=payload.putObject("input");input.put("synthetic",true);
        var json=new com.jinroon.jobe.domain.exploration.service.ExplorationJson(mapper);payload.put("inputHash",json.hex(input));
        payload.putArray("groupSummaries");payload.putArray("majorSummaries");
        // Store and session transition share the same transaction just as in the completion API.
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager());
        return Objects.requireNonNull(tx.execute(status->{long id=resultStore.save(session,payload).getId();jdbc.update("UPDATE exploration_session SET status='completed',completed_at=? WHERE id=?",SAME,session);return id;}));
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    private org.springframework.transaction.PlatformTransactionManager transactionManager(){return txManager;}
    private JsonNode page(String view,String token,String type,String status,Integer limit,String cursor,int expected)throws Exception {
        MockHttpServletRequestBuilder request=get(HISTORY+"/"+view).header("Authorization",token);
        if(type!=null)request.param("type",type);if(status!=null)request.param("status",status);
        if(limit!=null)request.param("limit",limit.toString());if(cursor!=null)request.param("cursor",cursor);
        return response(request,expected);
    }
    private JsonNode find(JsonNode rows,long id){for(JsonNode row:rows)if(row.path("sessionId").asLong()==id)return row;throw new AssertionError("missing history row");}
}
