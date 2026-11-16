package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.sharing.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties="exploration.sharing.enabled=true")
class ExplorationShareApiTest extends CompletedExplorationTestSupport {
    private static final Instant NOW=Instant.parse("2026-11-16T00:00:00Z");
    @MockitoBean ShareTime time;
    @Autowired ExplorationShareService sharing;
    @Autowired ShareTokenCodec tokens;
    @Autowired ExplorationSharePolicy sharePolicy;
    @Autowired ExplorationJson json;
    @BeforeEach void fixedTime(){when(time.now()).thenReturn(NOW);}
    @Test void initialStateIsDisabledAndReadOnlyAndOnlyExplicitPostIssuesALink()throws Exception {
        long rid=completedResult(true);JsonNode state=state(rid,200);
        assertThat(state.path("enabled").asBoolean()).isFalse();assertThat(state.path("available").asBoolean()).isTrue();assertThat(state.path("shareRevision").asLong()).isZero();
        assertThat(count("exploration_share_state","result_id=?",rid)).isZero();
        JsonNode issued=issue(rid,0,false,"create",201);assertThat(issued.path("shareRevision").asLong()).isEqualTo(1);assertThat(issued.path("issuedAt").asText()).isEqualTo(NOW.toString());
        assertThat(Instant.parse(issued.path("expiresAt").asText())).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(state(rid,200).path("shareToken")).isEqualTo(issued.path("shareToken"));read(issued,200);
    }
    @Test void anonymousSummaryIsAnExplicitAllowlistWithoutOwnerOrPrivateResultFields()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"create",201),summary=read(link,200);
        assertThat(summary.has("nickname")).isFalse();assertThat(summary.path("runMode").asText()).isEqualTo("DEV");assertThat(summary.path("testOnly").asBoolean()).isTrue();
        assertThat(summary.path("majors").size()).isLessThanOrEqualTo(7);assertThat(summary.path("groups").size()).isLessThanOrEqualTo(3);
        assertThat(allFields(summary)).doesNotContain("resultId","sessionId","userId","email","shareToken","majorCode","score","questionId","answer","caution","support","plan","vector","inputHash");
        for(JsonNode major:summary.path("majors"))assertThat(fields(major)).containsExactlyInAnyOrder("majorName","groupCode","explanation");
        assertThat(summary.path("nextActivities").isEmpty()).isTrue();assertThat(summary.path("activityAvailability").asText()).isEqualTo("NOT_INCLUDED");
        JsonNode flags=resultSnapshot(rid).path("flags");assertThat(summary.path("uncertainty").path("interestTie")).isEqualTo(flags.path("interestTie"));assertThat(summary.path("uncertainty").path("lowDifferentiation")).isEqualTo(flags.path("lowDifferentiation"));
    }
    @Test void nicknameIsOptInFrozenAndCanBeRemovedByRotatingTheLink()throws Exception {
        long rid=completedResult(true);jdbc.update("UPDATE users SET nickname='public QA nickname' WHERE id=?",OWNER);
        JsonNode link=issue(rid,0,true,"nick",201);assertThat(read(link,200).path("nickname").asText()).isEqualTo("public QA nickname");
        jdbc.update("UPDATE users SET nickname='changed later' WHERE id=?",OWNER);assertThat(read(link,200).path("nickname").asText()).isEqualTo("public QA nickname");
        JsonNode replacement=issue(rid,1,false,"remove",201);read(link,404);assertThat(read(replacement,200).has("nickname")).isFalse();
    }
    @Test void exactThirtyDayBoundaryExpiresAndRetryCannotExtendOriginalLink()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"create",201);Instant expiry=NOW.plus(Duration.ofDays(30));
        when(time.now()).thenReturn(expiry.minusNanos(1000));read(link,200);when(time.now()).thenReturn(expiry);read(link,404);
        assertThat(state(rid,200).path("enabled").asBoolean()).isFalse();assertThat(issue(rid,0,false,"create",201)).isEqualTo(link);read(link,404);
        JsonNode fresh=issue(rid,1,false,"fresh",201);assertThat(fresh.path("shareToken")).isNotEqualTo(link.path("shareToken"));read(fresh,200);
    }
    @Test void rotationAndRevocationInvalidateOldTokensAndDoNotResurrectOnReplay()throws Exception {
        long rid=completedResult(true);JsonNode first=issue(rid,0,false,"first",201),second=issue(rid,1,false,"second",201);
        read(first,404);read(second,200);assertThat(issue(rid,0,false,"first",201)).isEqualTo(first);read(first,404);
        JsonNode revoked=revoke(rid,2,"revoke",200);assertThat(revoked.path("shareRevision").asLong()).isEqualTo(3);read(second,404);
        JsonNode third=issue(rid,3,false,"third",201);assertThat(revoke(rid,2,"revoke",200)).isEqualTo(revoked);read(third,200);
        assertThat(count("exploration_share_link","state_id IN (SELECT id FROM exploration_share_state WHERE result_id=?) AND revoked_at IS NULL",rid)).isEqualTo(1);
    }
    @Test void revokeBeforeIssuanceAndRepeatedRevocationDoNotCreateOrIncrementState()throws Exception {
        long rid=completedResult(true);assertThat(revoke(rid,0,"empty",200).path("shareRevision").asLong()).isZero();
        assertThat(count("exploration_share_state","result_id=?",rid)).isZero();issue(rid,0,false,"first",201);revoke(rid,1,"revoke",200);
        assertThat(revoke(rid,2,"already",200).path("shareRevision").asLong()).isEqualTo(2);
    }
    @Test void sameKeyBodyConflictAndStaleRevisionDoNotCreateLinks()throws Exception {
        long rid=completedResult(true);issue(rid,0,false,"first",201);
        assertThat(issue(rid,0,true,"first",409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(issue(rid,0,false,"stale",409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(revoke(rid,0,"stale-revoke",409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(count("exploration_share_link","state_id IN (SELECT id FROM exploration_share_state WHERE result_id=?)",rid)).isEqualTo(1);
    }
    @Test void tokenPlaintextIsNotStoredInLinkOrGenericIdempotencyResponses()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"first",201);String token=link.path("shareToken").asText();
        String response=jdbc.queryForObject("SELECT response_json FROM exploration_idempotency WHERE user_id=? AND operation='SHARE_ISSUE' AND resource_id=?",String.class,OWNER,rid);
        String summary=jdbc.queryForObject("SELECT summary_json FROM exploration_share_link",String.class);
        assertThat(response).doesNotContain(token,"shareToken","publicPath");assertThat(summary).doesNotContain(token);
        byte[] nonce=jdbc.queryForObject("SELECT token_nonce FROM exploration_share_link",byte[].class),hash=jdbc.queryForObject("SELECT token_hash FROM exploration_share_link",byte[].class);
        assertThat(tokens.token(nonce)).isEqualTo(token);assertThat(hash).containsExactly(tokens.hash(token));
    }
    @Test void sameKeyConcurrentIssuesReturnOneTokenAndOneGeneration()throws Exception {
        long rid=completedResult(true);var replies=parallel(()->sharing.issue(OWNER,rid,"same",0,false));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());assertThat(count("exploration_share_link","state_id IN (SELECT id FROM exploration_share_state WHERE result_id=?)",rid)).isEqualTo(1);
    }
    @Test void differentConcurrentKeysHaveOneSuccessAndOneRevisionConflict()throws Exception {
        long rid=completedResult(true);var outcomes=parallel(()->{try{sharing.issue(OWNER,rid,UUID.randomUUID().toString(),0,false);return "SUCCESS";}catch(ExplorationApiException e){return e.getCode();}});
        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS","STALE_REVISION");
    }
    @Test void failedReplacementRestoresPreviousLinkRevisionAndAllowsSameKeyRetry()throws Exception {
        long rid=completedResult(true);JsonNode old=issue(rid,0,false,"old",201);
        jdbc.execute("CREATE TRIGGER test_share_issue_failure BEFORE INSERT ON exploration_share_link FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        try{issue(rid,1,false,"retry",500);read(old,200);assertThat(state(rid,200).path("shareRevision").asLong()).isEqualTo(1);}
        finally{jdbc.execute("DROP TRIGGER test_share_issue_failure");}
        JsonNode next=issue(rid,1,false,"retry",201);read(next,200);read(old,404);
    }
    @Test void idempotencyCommitFailureRollsBackStateAndEntireGeneration()throws Exception {
        long rid=completedResult(true);
        jdbc.execute("CREATE TRIGGER test_share_commit_failure BEFORE UPDATE ON exploration_idempotency FOR EACH ROW BEGIN IF NEW.operation='SHARE_ISSUE' AND NEW.response_status IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic commit failure'; END IF; END");
        try{issue(rid,0,false,"retry",500);assertThat(count("exploration_share_state","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND operation='SHARE_ISSUE'",OWNER)).isZero();}
        finally{jdbc.execute("DROP TRIGGER test_share_commit_failure");}
        read(issue(rid,0,false,"retry",201),200);
    }
    @Test void foreignOwnerCannotReadIssueOrRevokeAndPublicPathsCannotMutate()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"owner",201);
        response(get(path(rid)).header("Authorization",other),404);
        response(post(path(rid)).header("Authorization",other).header("Idempotency-Key","foreign").contentType(MediaType.APPLICATION_JSON).content(body(1,false)),404);
        response(post(path(rid)+"/revoke").header("Authorization",other).header("Idempotency-Key","foreign-revoke").contentType(MediaType.APPLICATION_JSON).content("{\"expectedShareRevision\":1}"),404);
        response(post(link.path("publicPath").asText()),401);response(get(path(rid)),401);
    }
    @Test void anonymousDevReadsRejectRemotePeersAndForwardedHeaders()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"link",201);String url=link.path("publicPath").asText();
        response(get(url).with(r->{r.setRemoteAddr("203.0.113.10");return r;}),404);
        response(get(url).with(r->{r.setRemoteAddr("203.0.113.10");return r;}).header("X-Forwarded-For","127.0.0.1"),404);
        for(String header:List.of("Forwarded","X-Forwarded-For","X-Real-IP","X-Forwarded-Host","X-Forwarded-Proto"))response(get(url).header(header,"127.0.0.1"),404);
        response(get(url).with(r->{r.setRemoteAddr("::1");return r;}),200);
    }
    @Test void cacheReferrerAndRobotsHeadersApplyToSuccessErrorsAndOwnerIssuance()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"link",201);String url=link.path("publicPath").asText();
        for(String target:List.of(url,"/api/exploration/v1/shared-results/invalid"))mvc.perform(get(target)).andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(header().string("X-Robots-Tag","noindex, nofollow"));
        mvc.perform(get(path(rid)).header("Authorization",owner)).andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void malformedUnknownRevokedAndExpiredTokensHaveSameNotFoundResponse()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"link",201);
        JsonNode missing=response(get("/api/exploration/v1/shared-results/invalid"),404);
        assertThat(response(get("/api/exploration/v1/shared-results/"+tokens.token(tokens.nonce())),404)).isEqualTo(missing);
        revoke(rid,1,"revoke",200);assertThat(read(link,404)).isEqualTo(missing);
        JsonNode fresh=issue(rid,2,false,"new",201);when(time.now()).thenReturn(NOW.plus(Duration.ofDays(30)));assertThat(read(fresh,404)).isEqualTo(missing);
    }
    @Test void strictRevisionNicknameHeadersAndQueriesCannotOverrideServerDecisions()throws Exception {
        long rid=completedResult(true);
        for(String body:List.of("{}","{\"expectedRevision\":0}","{\"expectedShareRevision\":\"0\"}","{\"expectedShareRevision\":0.5}","{\"expectedShareRevision\":-1}","{\"expectedShareRevision\":0,\"includeNickname\":\"true\"}","{\"expectedShareRevision\":0,\"expiresAt\":\"2099-01-01\"}","{\"expectedShareRevision\":0,\"userId\":1}","{\"expectedShareRevision\":0,\"runMode\":\"PUBLIC\"}"))response(post(path(rid)).header("Authorization",owner).header("Idempotency-Key","bad").contentType(MediaType.APPLICATION_JSON).content(body),400);
        response(post(path(rid)).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body(0,false)),400);
        response(get(path(rid)+"?userId=1").header("Authorization",owner),400);
        JsonNode link=issue(rid,0,false,"link",201);response(get(link.path("publicPath").asText()+"?mode=PUBLIC"),400);
    }
    @Test void noCandidateCanBeSharedWithoutInventedMajorsOrActivities()throws Exception {
        long rid=completedResult(false);JsonNode summary=read(issue(rid,0,false,"empty",201),200);
        assertThat(summary.path("majors").isEmpty()).isTrue();assertThat(summary.path("groups").isEmpty()).isTrue();assertThat(summary.path("uncertainty").path("noSupportedCandidate").asBoolean()).isTrue();assertThat(summary.path("nextActivities").isEmpty()).isTrue();
    }
    @Test void shareMutationsDoNotChangeSourceResultSessionOrSupport()throws Exception {
        long rid=completedResult(true),sid=resultSnapshot(rid).path("sessionId").asLong(),rev=sessionRevision(sid);String before=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        JsonNode link=issue(rid,0,false,"create",201);read(link,200);revoke(rid,1,"revoke",200);
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(before);assertThat(sessionRevision(sid)).isEqualTo(rev);assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
    }
    @Test void corruptionAndAppendedPrivateFieldsFailClosedEvenWithRecomputedSummaryHash()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"link",201);
        ObjectNode summary=(ObjectNode)json.read(jdbc.queryForObject("SELECT summary_json FROM exploration_share_link",String.class));summary.put("email","private@example.invalid");
        jdbc.update("UPDATE exploration_share_link SET summary_json=?,summary_hash=?",json.write(summary),json.hex(summary));assertThat(read(link,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE id=?",rid);read(link,503);
    }
    @Test void inactiveAccountAndWithdrawalBlockLinksAndCascadePersonalSharingData()throws Exception {
        long rid=completedResult(true);JsonNode first=issue(rid,0,false,"first",201);issue(rid,1,false,"second",201);long state=jdbc.queryForObject("SELECT id FROM exploration_share_state WHERE result_id=?",Long.class,rid);
        JsonNode current=state(rid,200);jdbc.update("UPDATE users SET status='suspended' WHERE id=?",OWNER);read(current,404);jdbc.update("UPDATE users SET status='active' WHERE id=?",OWNER);read(current,200);
        deletion.delete(OWNER);read(current,404);
        assertThat(count("exploration_share_state","result_id=?",rid)).isZero();assertThat(count("exploration_share_link","state_id=?",state)).isZero();
        assertThat(count("exploration_idempotency","user_id=? AND operation LIKE 'SHARE_%'",OWNER)).isZero();response(get(path(rid)).header("Authorization",owner),401);
    }
    @Test void revocationStillWorksWhenSharingIsTurnedOff()throws Exception {
        long rid=completedResult(true);JsonNode link=issue(rid,0,false,"link",201);
        org.springframework.test.util.ReflectionTestUtils.setField(sharePolicy,"enabled",false);
        try{read(link,404);assertThat(state(rid,200).path("available").asBoolean()).isFalse();issue(rid,1,false,"disabled",503);revoke(rid,1,"revoke-disabled",200);}
        finally{org.springframework.test.util.ReflectionTestUtils.setField(sharePolicy,"enabled",true);}
        read(link,404);
    }
    private String path(long rid){return "/api/exploration/v1/results/"+rid+"/share";}
    private String body(long rev,boolean nickname)throws Exception{return mapper.writeValueAsString(Map.of("expectedShareRevision",rev,"includeNickname",nickname));}
    private JsonNode state(long rid,int status)throws Exception{return response(get(path(rid)).header("Authorization",owner),status);}
    private JsonNode issue(long rid,long rev,boolean nickname,String key,int status)throws Exception{return response(post(path(rid)).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(rev,nickname)),status);}
    private JsonNode revoke(long rid,long rev,String key,int status)throws Exception{return response(post(path(rid)+"/revoke").header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"expectedShareRevision\":"+rev+"}"),status);}
    private JsonNode read(JsonNode link,int status)throws Exception{return response(get(link.path("publicPath").asText()),status);}
    private Set<String> fields(JsonNode node){Set<String> names=new HashSet<>();node.fieldNames().forEachRemaining(names::add);return names;}
    private Set<String> allFields(JsonNode node){Set<String> names=new HashSet<>();if(node.isObject())names.addAll(fields(node));if(node.isContainerNode())node.forEach(child->names.addAll(allFields(child)));return names;}
}
