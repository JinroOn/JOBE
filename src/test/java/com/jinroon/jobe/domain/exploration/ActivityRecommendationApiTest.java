package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.activity.ActivityCatalogLoader;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.TargetType;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import com.jinroon.jobe.domain.exploration.activity.ActivityRecommendationService;

/** All positive approvals here are synthetic rows in an isolated test DB, never real content approval. */
@TestPropertySource(properties="exploration.activities.catalog-version=TEST_ACTIVITY_RECOMMENDATION_V1")
class ActivityRecommendationApiTest extends CompletedExplorationTestSupport {
    @Autowired ActivityCatalogLoader activityLoader;
    @Autowired CatalogReviewService reviewService;
    @Autowired PostResultSupportService support;
    @Autowired ActivityRecommendationService recommendations;
    @MockitoSpyBean JdbcTemplate recommendationJdbc;
    private static final String VERSION="TEST_ACTIVITY_RECOMMENDATION_V1";
    private final Set<Long> createdCatalogs=Collections.synchronizedSet(new HashSet<>());
    private long protectedCatalogId;
    private Map<String,Object> protectedCatalog;
    private List<Map<String,Object>> protectedItems,protectedReviews;
    private boolean protectedCatalogCaptured;

    @BeforeEach void preserveActualDraftCatalog(){
        activityLoader.load(Path.of("datasets/activities/catalog.json"));
        protectedCatalogId=jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version='ACTIVITY_CATALOG_V1_DRAFT'",Long.class);
        protectedCatalog=jdbc.queryForMap("SELECT * FROM exploration_activity_catalog WHERE id=?",protectedCatalogId);
        protectedItems=jdbc.queryForList("SELECT * FROM exploration_activity WHERE catalog_id=? ORDER BY id",protectedCatalogId);
        protectedReviews=reviewsFor(protectedCatalogId);
        protectedCatalogCaptured=true;
        assertThat(count("exploration_activity_catalog","version=?",VERSION)).as("Discard the dedicated test DB if a stale synthetic fixture remains").isZero();
    }
    @AfterEach void clearActivities(){
        for(long id:createdCatalogs)removeCatalog(id);
        createdCatalogs.clear();
        if(!protectedCatalogCaptured)return;
        assertThat(jdbc.queryForMap("SELECT * FROM exploration_activity_catalog WHERE id=?",protectedCatalogId)).isEqualTo(protectedCatalog);
        assertThat(jdbc.queryForList("SELECT * FROM exploration_activity WHERE catalog_id=? ORDER BY id",protectedCatalogId)).isEqualTo(protectedItems);
        assertThat(reviewsFor(protectedCatalogId)).isEqualTo(protectedReviews);
    }
    private List<Map<String,Object>> reviewsFor(long catalog){
        return jdbc.queryForList("SELECT * FROM exploration_review_history WHERE (target_type='ACTIVITY_CATALOG' AND target_id=?) "
                +"OR (target_type='ACTIVITY' AND target_id IN (SELECT id FROM exploration_activity WHERE catalog_id=?)) ORDER BY id",catalog,catalog);
    }
    private void removeCatalog(long catalog){
        jdbc.update("DELETE FROM exploration_review_history WHERE (target_type='ACTIVITY_CATALOG' AND target_id=?) "
                +"OR (target_type='ACTIVITY' AND target_id IN (SELECT id FROM exploration_activity WHERE catalog_id=?))",catalog,catalog);
        jdbc.update("DELETE FROM exploration_activity WHERE catalog_id=?",catalog);
        jdbc.update("DELETE FROM exploration_activity_catalog WHERE id=?",catalog);
    }
    private long catalogId(){return jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version=?",Long.class,VERSION);}
    private ActivityCatalogLoader.Report loadCatalog(JsonNode payload){
        var report=activityLoader.load(payload);
        if(report.inserted())createdCatalogs.add(jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version=?",Long.class,report.version()));
        return report;
    }
    @Test void absentCatalogReturnsExplicitEmptyStateWithoutCreatingSupport()throws Exception {
        long rid=completedResult(true);JsonNode response=activities(rid,200);
        assertThat(response.path("activityAvailability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.path("reasonCode").asText()).isEqualTo("NO_ACTIVITY_CATALOG");
        assertThat(response.path("nextActivities").isEmpty()).isTrue();
        assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
    }
    @Test void actual282DraftsImportUnchangedAndAreNeverOffered()throws Exception {
        long rid=completedResult(true);
        assertThat(activityLoader.load(Path.of("datasets/activities/catalog.json")).activities()).isEqualTo(282);
        assertThat(activityLoader.load(Path.of("datasets/activities/catalog.json")).inserted()).isFalse();
        assertThat(count("exploration_activity","catalog_id=? AND content_status='DRAFT' AND domain_review_status='PENDING'",protectedCatalogId)).isEqualTo(282);
        assertThat(reviewsFor(protectedCatalogId)).isEqualTo(protectedReviews);
        ReflectionTestUtils.setField(recommendations,"selectedVersion","ACTIVITY_CATALOG_V1_DRAFT");
        try {
            JsonNode response=activities(rid,200);assertThat(response.path("nextActivities").isEmpty()).isTrue();
            assertThat(response.path("reasonCode").asText()).isEqualTo("NO_APPROVED_ACTIVITIES");
            assertThat(response.toString()).doesNotContain("body_markdown","worksheet_markdown","review_record_markdown");
        } finally {ReflectionTestUtils.setField(recommendations,"selectedVersion",VERSION);}
    }
    @Test void resultAndActivityReadsReuseVerifiedInputForTheFullCatalog() throws Exception {
        long resultId = completedResult(true);
        long sessionId = resultSnapshot(resultId).path("sessionId").asLong();
        var fixture = (ObjectNode) mapper.readTree(java.nio.file.Files.readString(Path.of("datasets/activities/catalog.json")));
        fixture.put("version", VERSION);
        assertThat(loadCatalog(fixture).activities()).isEqualTo(282);
        approveAll();

        JsonNode state = support.select(OWNER, resultId, "choose", 0, "ACTIVITY", null).body();
        support.answer(OWNER, resultId, issued(state, "ACTIVITY-REPORT-01"), "preference", 1, "AGREE", "preference");

        clearInvocations(recommendationJdbc);
        var activities = activities(resultId, 200);
        assertReadQueryCounts();
        assertThat(activities.path("supportRevision").asLong()).isEqualTo(2);
        assertThat(activities.path("nextActivities").size()).isBetween(1, 3);
        assertThat(activities.path("catalogReviewIds").size()).isEqualTo(2);
        activities.path("nextActivities").forEach(card -> assertThat(card.path("reviewIds").size()).isEqualTo(2));

        clearInvocations(recommendationJdbc);
        var result = getJson("/" + sessionId + "/result", owner, 200);
        assertReadQueryCounts();
        assertThat(result.path("nextActivities")).isEqualTo(activities.path("nextActivities"));

        clearInvocations(recommendationJdbc);
        var resultById = response(get("/api/exploration/v1/results/" + resultId).header("Authorization", owner), 200);
        assertReadQueryCounts();
        assertThat(resultById).isEqualTo(result);
    }

    private void assertReadQueryCounts() {
        assertThat(queryCount("FROM exploration_review_history")).isEqualTo(2);
        assertThat(queryCount("FROM exploration_result_seal")).isEqualTo(1);
        assertThat(queryCount("FROM exploration_session_pin")).isEqualTo(1);
    }

    private long queryCount(String fragment) {
        return mockingDetails(recommendationJdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("queryForList"))
                .filter(invocation -> invocation.getArgument(0, String.class).contains(fragment))
                .count();
    }
    @Test void statusesAloneOrOnlyRootApprovalNeverAuthorizeItems()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));
        statuses("exploration_activity_catalog");statuses("exploration_activity");
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
        approveRoot();assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
        approveItem("A");assertThat(activities(rid,200).path("nextActivities").size()).isEqualTo(1);
    }
    @Test void authoredGeneralDraftsNeedReviewsAndUseExplicitPreferencesForNoCandidateResults()throws Exception {
        long rid=completedResult(false);
        ObjectNode next=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(Path.of("datasets/activities/catalog-v2.json")));
        next.put("version",VERSION);loadCatalog(next);
        assertThat(activities(rid,200).path("reasonCode").asText()).isEqualTo("NO_APPROVED_ACTIVITIES");
        approveRoot();statuses("exploration_activity");
        for(JsonNode a:next.path("activities"))if(a.path("major_code").isNull())approveItem(a.path("activity_id").asText());
        String raw=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        JsonNode state=support.select(OWNER,rid,"common-select",0,"ACTIVITY",null).body();
        support.answer(OWNER,rid,issued(state,"ACTIVITY-EXPERIMENT-01"),"common-positive",1,"STRONGLY_AGREE","common-positive");
        var response=activities(rid,200);
        assertThat(ids(response).get(0)).isEqualTo("DRAFT-GENERAL-04");
        assertThat(response.path("nextActivities").size()).isEqualTo(3);
        for(JsonNode a:response.path("nextActivities")){
            assertThat(a.path("major_code").isNull()).isTrue();
            assertThat(a.path("selectionReason").asText()).isEqualTo("GENERAL_EXPLORATION");
        }
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(raw);
        review("exploration_activity","ACTIVITY","DOMAIN","REJECTED");
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
    }

    @Test void reviewDraftImportsNineActivitiesButOnlyTheReviewedItemCanBeOffered() throws Exception {
        long result = completedResult(false);
        var draft = activityLoader.validate(Path.of("datasets/activities/catalog-v3-dev-review-draft.json"));
        assertThat(draft.path("version").asText()).isEqualTo("ACTIVITY_CATALOG_V3_DEV_REVIEW_DRAFT");
        assertThat(loadCatalog(draft).activities()).isEqualTo(9);
        assertThat(loadCatalog(draft).inserted()).isFalse();
        long catalog = jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version=?",
                Long.class, draft.path("version").asText());
        assertThat(count("exploration_activity", "catalog_id=? AND major_code='MAJOR_ROW_133'", catalog)).isEqualTo(3);
        assertThat(count("exploration_activity", "catalog_id=? AND major_code IS NULL", catalog)).isEqualTo(6);
        assertThat(count("exploration_activity", "catalog_id=? AND content_status='DRAFT' AND "
                + "implementation_review_status='PENDING' AND domain_review_status='PENDING'", catalog)).isEqualTo(9);
        assertThat(reviewsFor(catalog)).isEmpty();

        ReflectionTestUtils.setField(recommendations, "selectedVersion", draft.path("version").asText());
        try {
            assertThat(activities(result, 200).path("reasonCode").asText()).isEqualTo("NO_APPROVED_ACTIVITIES");
            var target = jdbc.queryForObject("SELECT id FROM exploration_activity WHERE catalog_id=? AND activity_code=?",
                    Long.class, catalog, "DRAFT-GENERAL-04");
            approveReviewDraftTarget(TargetType.ACTIVITY_CATALOG, catalog);
            assertThat(ids(activities(result, 200))).isEmpty();
            approveReviewDraftTarget(TargetType.ACTIVITY, target);
            assertThat(ids(activities(result, 200))).containsExactly("DRAFT-GENERAL-04");
        } finally {
            ReflectionTestUtils.setField(recommendations, "selectedVersion", VERSION);
        }
    }

    private void approveReviewDraftTarget(TargetType type, long id) {
        for (var kind : List.of(CatalogReviewService.ReviewKind.IMPLEMENTATION, CatalogReviewService.ReviewKind.DOMAIN)) {
            reviewService.record(OTHER, type, id, kind, CatalogReviewService.Decision.APPROVED, "SYNTHETIC-TEST-ONLY");
        }
    }
    @Test void authoredMajorReadingAndProjectStepsDrivePreferenceWithoutChangingCandidateOrder()throws Exception {
        long rid=completedResult(true);String code=major(rid,0);
        ObjectNode next=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(Path.of("datasets/activities/catalog-v2.json")));
        next.put("version",VERSION);loadCatalog(next);approveRoot();statuses("exploration_activity");
        String reading=null,project=null;
        for(JsonNode a:next.path("activities"))if(code.equals(a.path("major_code").asText())){
            if(a.path("kind").asText().equals("INFORMATION"))reading=a.path("activity_id").asText();
            if(a.path("kind").asText().equals("SMALL_TASK"))project=a.path("activity_id").asText();
        }
        assertThat(reading).isNotNull();assertThat(project).isNotNull();approveItem(reading);approveItem(project);
        JsonNode state=support.select(OWNER,rid,"major-select",0,"ACTIVITY",null).body();
        support.answer(OWNER,rid,issued(state,"ACTIVITY-READ-01"),"read-negative",1,"STRONGLY_DISAGREE","read-negative");
        support.answer(OWNER,rid,issued(state,"ACTIVITY-PROJECT-01"),"project-positive",2,"AGREE","project-positive");
        assertThat(ids(activities(rid,200))).containsExactly(project,reading);
    }
    @Test void approvalsRequireExactHashEvidenceScopeAndBothKinds()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        for(String sql:List.of("target_hash=REPEAT('0',64)","evidence_reference=''","scope='PUBLIC'","decision='REJECTED'","reviewer_id=0","reviewed_at=DATE_ADD(NOW(),INTERVAL 1 DAY)")) {
            var original=jdbc.queryForMap("SELECT * FROM exploration_review_history WHERE target_type='ACTIVITY' AND review_kind='DOMAIN' AND target_id IN (SELECT id FROM exploration_activity WHERE catalog_id=?)",catalogId());
            jdbc.update("UPDATE exploration_review_history SET "+sql+" WHERE id=?",original.get("id"));
            assertThat(activities(rid,200).path("nextActivities").isEmpty()).as(sql).isTrue();
            jdbc.update("UPDATE exploration_review_history SET target_hash=?,evidence_reference=?,scope=?,decision=?,reviewer_id=?,reviewed_at=? WHERE id=?",original.get("target_hash"),original.get("evidence_reference"),original.get("scope"),original.get("decision"),original.get("reviewer_id"),original.get("reviewed_at"),original.get("id"));
        }
        jdbc.update("DELETE FROM exploration_review_history WHERE target_type='ACTIVITY' AND review_kind='IMPLEMENTATION' AND target_id IN (SELECT id FROM exploration_activity WHERE catalog_id=?)",catalogId());
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
    }
    @Test void latestRejectionRevokesOldApprovalImmediately()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        assertThat(activities(rid,200).path("nextActivities").size()).isEqualTo(1);
        review("exploration_activity","ACTIVITY","DOMAIN","REJECTED");
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
    }
    @Test void archivedOrRejectedItemsCannotBeReenabledByOldReviews()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        jdbc.update("UPDATE exploration_activity SET content_status='ARCHIVED' WHERE catalog_id=?",catalogId());
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
        jdbc.update("UPDATE exploration_activity SET content_status='REVIEWED',domain_review_status='REJECTED' WHERE catalog_id=?",catalogId());
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
    }
    @Test void targetSelectsOnlyThatFrozenCandidateAndGeneralActivities()throws Exception {
        long rid=completedResult(true);String a=major(rid,0),b=major(rid,1);
        load(item("A",a),item("B",b),item("C",null));approveAll();
        support.select(OWNER,rid,"target",0,"ACTIVITY",b);
        JsonNode response=activities(rid,200);
        assertThat(ids(response)).containsExactly("B","C");
        assertThat(response.path("supportRevision").asLong()).isEqualTo(1);
    }
    @Test void noCandidateReceivesOnlyApprovedGeneralExplorationAndCanBeEmpty()throws Exception {
        long rid=completedResult(false);load(item("A","MAJOR_ROW_133"),item("B",null));approveAll();
        assertThat(ids(activities(rid,200))).containsExactly("B");
        jdbc.update("UPDATE exploration_activity SET content_status='ARCHIVED' WHERE activity_code='B' AND catalog_id=?",catalogId());
        assertThat(activities(rid,200).path("nextActivities").isEmpty()).isTrue();
    }
    @Test void candidateOrderLimitAndTieBreakAreStable()throws Exception {
        long rid=completedResult(true);String first=major(rid,0),second=major(rid,1);
        load(item("D",second),item("C",first),item("B",first),item("A",first));approveAll();
        assertThat(ids(activities(rid,200))).containsExactly("A","B","C");
        assertThat(ids(activities(rid,200))).containsExactly("A","B","C");
    }
    @Test void effectivePreferenceUsesFrozenNumericMeaningIncludingZeroAndUnknownIsNeutral()throws Exception {
        long rid=completedResult(true);String major=major(rid,0);
        ObjectNode a=item("A",major),b=item("B",major);a.putArray("preference_question_ids").add("ACTIVITY-REPORT-01");b.putArray("preference_question_ids").add("ACTIVITY-PROJECT-01");
        load(a,b);approveAll();
        JsonNode state=support.select(OWNER,rid,"choose",0,"ACTIVITY",null).body();
        long report=issued(state,"ACTIVITY-REPORT-01"),project=issued(state,"ACTIVITY-PROJECT-01");
        support.answer(OWNER,rid,report,"zero",1,"STRONGLY_DISAGREE","zero");
        support.answer(OWNER,rid,project,"high",2,"AGREE","high");
        assertThat(ids(activities(rid,200))).containsExactly("B","A");
        support.answer(OWNER,rid,report,"unknown",3,"UNKNOWN","unknown");
        support.answer(OWNER,rid,project,"unknown2",4,"UNKNOWN","unknown2");
        assertThat(ids(activities(rid,200))).containsExactly("A","B");
        support.answer(OWNER,rid,report,"high2",5,"AGREE","high2");
        support.select(OWNER,rid,"plan",6,"PLAN",null);
        assertThat(ids(activities(rid,200))).containsExactly("A","B");
        assertThat(activities(rid,200).path("nextActivities").get(0).path("preferenceApplied").asBoolean()).isFalse();
    }
    @Test void availableCardsContainApprovedContentSourcesAndEvidenceIdsWithoutInternalReviewNotes()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        JsonNode response=activities(rid,200),card=response.path("nextActivities").get(0);
        assertThat(response.path("activityAvailability").asText()).isEqualTo("AVAILABLE");
        for(String field:List.of("body_markdown","example_output","worksheet_markdown","sources","reviewIds","contentHash"))assertThat(card.has(field)).as(field).isTrue();
        assertThat(card.toString()).doesNotContain("review_record_markdown","SYNTHETIC_REVIEW_NOTE");
        assertThat(response.path("catalogReviewIds").size()).isEqualTo(2);
        JsonNode result=response(get("/api/exploration/v1/results/"+rid).header("Authorization",owner),200);
        assertThat(result.path("nextActivities")).isEqualTo(response.path("nextActivities"));
        long sid=result.path("sessionId").asLong();
        assertThat(getJson("/"+sid+"/result",owner,200).path("nextActivities")).isEqualTo(result.path("nextActivities"));
    }
    @Test void readingRecommendationsDoesNotWritePersonalDataOrModifyResultAndRevision()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        String stored=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        long sid=jdbc.queryForObject("SELECT session_id FROM exploration_result WHERE id=?",Long.class,rid),rev=sessionRevision(sid);
        int requests=count("exploration_idempotency","user_id=?",OWNER);
        activities(rid,200);activities(rid,200);
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(stored);
        assertThat(sessionRevision(sid)).isEqualTo(rev);assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
        assertThat(count("exploration_idempotency","user_id=?",OWNER)).isEqualTo(requests);
    }
    @Test void tamperedRootOrActivityFailsClosedInsteadOfReturningAnEmptySuccess()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        jdbc.update("UPDATE exploration_activity SET payload_json=JSON_SET(payload_json,'$.title','tampered') WHERE catalog_id=?",catalogId());
        assertThat(activities(rid,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        jdbc.update("UPDATE exploration_activity_catalog SET payload_json=JSON_SET(payload_json,'$.version','tampered') WHERE id=?",catalogId());
        activities(rid,503);
    }
    @Test void rootBindingDetectsChangedItemEvenWhenItsIndividualHashIsRecomputed()throws Exception {
        long rid = completedResult(true);
        long sid = resultSnapshot(rid).path("sessionId").asLong();
        String selected = major(rid, 0);
        ObjectNode unrelated = item("B", major(rid, 1));
        load(item("A", selected), unrelated);
        approveAll();
        support.select(OWNER, rid, "choose", 0, "ACTIVITY", selected);
        assertThat(ids(activities(rid, 200))).containsExactly("A");

        unrelated.put("title", "changed");
        String changed = mapper.writeValueAsString(unrelated);
        String changedHash = new com.jinroon.jobe.domain.exploration.service.ExplorationJson(mapper).hex(unrelated);
        jdbc.update("UPDATE exploration_activity SET payload_json=?,content_hash=? WHERE catalog_id=? AND activity_code='B'",
                changed, changedHash, catalogId());
        assertThat(activities(rid, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        assertThat(getJson("/" + sid + "/result", owner, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
    }
    @Test void catalogRejectsRowsAddedOrReplacedOutsideTheSealedBody() throws Exception {
        long result = completedResult(true);
        load(item("A", major(result, 0)));
        approveAll();

        jdbc.update("""
                INSERT INTO exploration_activity (catalog_id, activity_code, version, major_code, content_hash,
                       payload_json, content_status, implementation_review_status, domain_review_status)
                SELECT catalog_id, 'EXTRA', version, major_code, content_hash, payload_json,
                       content_status, implementation_review_status, domain_review_status
                FROM exploration_activity WHERE catalog_id=? AND activity_code='A'
                """, catalogId());
        assertThat(activities(result, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");

        jdbc.update("DELETE FROM exploration_activity WHERE catalog_id=? AND activity_code='A'", catalogId());
        assertThat(activities(result, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
    }

    @Test void mismatchedMajorMappingVersionReturnsUnavailable()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();
        // A legitimately versioned catalog for another mapping is unavailable to this old result.
        ObjectNode payload=(ObjectNode)mapper.readTree(jdbc.queryForObject("SELECT payload_json FROM exploration_activity_catalog WHERE id=?",String.class,catalogId()));
        payload.put("major_mapping_version","ANOTHER_MAPPING");var json=new com.jinroon.jobe.domain.exploration.service.ExplorationJson(mapper);
        jdbc.update("UPDATE exploration_activity_catalog SET major_mapping_version=?,payload_json=?,content_hash=? WHERE id=?","ANOTHER_MAPPING",json.write(payload),json.hex(payload),catalogId());
        assertThat(activities(rid,200).path("reasonCode").asText()).isEqualTo("MAPPING_VERSION_UNAVAILABLE");
    }
    @Test void clientCannotOverrideCatalogApprovalUserOrTargetAndForeignResultsAreHidden()throws Exception {
        long rid=completedResult(true);
        for(String query:List.of("approvedOnly=false","catalogVersion=test","targetMajorCode=ANY","userId=1","runMode=PUBLIC"))response(get(path(rid)+"?"+query).header("Authorization",owner),400);
        response(get(path(rid)).header("Authorization",other),404);response(get(path(rid)),401);
        response(get(path(Long.MAX_VALUE)).header("Authorization",owner),404);
    }
    @Test void withdrawalKeepsSharedCatalogAndRemovesPersonalSupportAndAccess()throws Exception {
        long rid=completedResult(true);load(item("A",major(rid,0)));approveAll();support.select(OWNER,rid,"choose",0,"ACTIVITY",null);
        deletion.delete(OWNER);response(get(path(rid)).header("Authorization",owner),401);
        assertThat(count("exploration_activity_catalog","version=?",VERSION)).isEqualTo(1);
        assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
    }
    @Test void loaderRejectsConflictsUnknownMajorsAndRollsBackPartialImport()throws Exception {
        long rid=completedResult(true);ObjectNode a=item("A",major(rid,0));ObjectNode payload=payload(a);loadCatalog(payload);
        a.put("title","different");assertThatThrownBy(()->loadCatalog(payload(a))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode wrong=payload(item("B","NO_SUCH_MAJOR"));wrong.put("version","TEST_ACTIVITY_RECOMMENDATION_WRONG_MAJOR");
        assertThatThrownBy(()->loadCatalog(wrong)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode rollback=payload(item("B",major(rid,0)),item("C",major(rid,0)));rollback.put("version","TEST_ACTIVITY_RECOMMENDATION_ROLLBACK");
        jdbc.execute("CREATE TRIGGER test_activity_import_failure BEFORE INSERT ON exploration_activity FOR EACH ROW BEGIN IF NEW.activity_code='C' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");
        try{assertThatThrownBy(()->loadCatalog(rollback)).isInstanceOf(Exception.class);assertThat(count("exploration_activity_catalog","version='TEST_ACTIVITY_RECOMMENDATION_ROLLBACK'")).isZero();}
        finally{jdbc.execute("DROP TRIGGER test_activity_import_failure");}
    }
    @Test void concurrentSameVersionImportsAreAtomicAndIdempotent()throws Exception {
        long rid=completedResult(true);ObjectNode payload=payload(item("A",major(rid,0)),item("B",major(rid,0)));
        var reports=parallel(()->loadCatalog(payload));
        assertThat(reports.stream().filter(ActivityCatalogLoader.Report::inserted).count()).isEqualTo(1);
        assertThat(count("exploration_activity_catalog","version=?",VERSION)).isEqualTo(1);
        assertThat(count("exploration_activity","catalog_id=? AND activity_code IN ('A','B')",catalogId())).isEqualTo(2);
    }
    @Test void reimportDoesNotRepairDamagedStoredContentSilently()throws Exception {
        long rid=completedResult(true);ObjectNode payload=payload(item("A",major(rid,0)));loadCatalog(payload);
        jdbc.update("UPDATE exploration_activity SET payload_json=JSON_SET(payload_json,'$.title','damaged') WHERE catalog_id=?",catalogId());
        assertThatThrownBy(()->loadCatalog(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cleanupPreservesAnUnownedCatalogAndItsRowsAlongsideTheActualDrafts()throws Exception {
        long rid=completedResult(true);
        ObjectNode foreign=payload(item("UNRELATED",major(rid,0)));foreign.put("version","TEST_ACTIVITY_UNRELATED_"+UUID.randomUUID().toString().replace("-",""));
        activityLoader.load(foreign); // Deliberately outside the owned fixture set.
        long id=jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version=?",Long.class,foreign.path("version").asText());
        var before=jdbc.queryForMap("SELECT * FROM exploration_activity_catalog WHERE id=?",id);
        var items=jdbc.queryForList("SELECT * FROM exploration_activity WHERE catalog_id=?",id);
        try {
            load(item("A",major(rid,0)));approveAll();
            clearActivities();
            assertThat(jdbc.queryForMap("SELECT * FROM exploration_activity_catalog WHERE id=?",id)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM exploration_activity WHERE catalog_id=?",id)).isEqualTo(items);
            assertThat(count("exploration_activity_catalog","version=?",VERSION)).isZero();
        } finally {removeCatalog(id);}
    }

    private ObjectNode item(String code,String major){
        ObjectNode a=mapper.createObjectNode();a.put("activity_id",code);a.put("version","TEST-1");a.put("major_code",major);
        a.put("kind","SMALL_TASK");a.put("title","Synthetic activity "+code);a.put("body_markdown","Synthetic activity body");
        a.put("worksheet_markdown","Synthetic worksheet");a.put("example_output","Synthetic example");a.put("review_record_markdown","SYNTHETIC_REVIEW_NOTE");a.put("alternative","Synthetic alternative");
        a.put("score_effect","NONE");a.put("changes_recommendation",false);draft(a);a.putArray("preference_question_ids");a.putArray("steps").add("Synthetic step");a.putArray("artifacts").add("Synthetic artifact");
        var source=a.putArray("sources").addObject();source.put("source_id","TEST-SOURCE");source.put("title","Synthetic source");source.put("publisher","Synthetic test");source.put("url","https://example.invalid/activity");return a;
    }
    private ObjectNode payload(ObjectNode... items){ObjectNode p=mapper.createObjectNode();p.put("schema_version","1.0");p.put("version",VERSION);p.put("major_mapping_version","MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE");draft(p);var array=p.putArray("activities");for(var item:items)array.add(item);return p;}
    private void draft(ObjectNode a){a.put("content_status","DRAFT");a.put("implementation_review_status","PENDING");a.put("domain_review_status","PENDING");}
    private void load(ObjectNode... items){loadCatalog(payload(items));}
    private void statuses(String table){jdbc.update("UPDATE "+table+" SET content_status='REVIEWED',implementation_review_status='APPROVED_FOR_IMPLEMENTATION',domain_review_status='APPROVED' WHERE "+(table.equals("exploration_activity_catalog")?"id":"catalog_id")+"=?",catalogId());}
    private void approveAll(){statuses("exploration_activity_catalog");statuses("exploration_activity");approveRoot();for(var code:jdbc.queryForList("SELECT activity_code FROM exploration_activity WHERE catalog_id=?",String.class,catalogId()))approveItem(code);}
    private void approveRoot(){statuses("exploration_activity_catalog");review("exploration_activity_catalog","ACTIVITY_CATALOG","IMPLEMENTATION","APPROVED_FOR_IMPLEMENTATION");review("exploration_activity_catalog","ACTIVITY_CATALOG","DOMAIN","APPROVED");}
    private void approveItem(String code){reviewWhere("exploration_activity","ACTIVITY","IMPLEMENTATION","APPROVED_FOR_IMPLEMENTATION"," WHERE activity_code='"+code+"'");reviewWhere("exploration_activity","ACTIVITY","DOMAIN","APPROVED"," WHERE activity_code='"+code+"'");}
    private void review(String table,String type,String kind,String decision){reviewWhere(table,type,kind,decision,"");}
    private void reviewWhere(String table,String type,String kind,String decision,String where){jdbc.update("INSERT INTO exploration_review_history(target_type,target_id,target_version,target_hash,review_kind,decision,reviewer_id,reviewed_at,evidence_reference,scope) SELECT ?,id,version,content_hash,?,?,999999,NOW(),'SYNTHETIC-TEST-ONLY','DEV' FROM "+table+where+(where.isEmpty()?" WHERE ":" AND ")+(table.equals("exploration_activity_catalog")?"id":"catalog_id")+"=?",type,kind,decision,catalogId());}
    private String major(long rid,int index)throws Exception{return resultSnapshot(rid).path("rankedCandidates").get(index).path("majorCode").asText();}
    private String path(long rid){return "/api/exploration/v1/results/"+rid+"/activities";}
    private JsonNode activities(long rid,int status)throws Exception{return response(get(path(rid)).header("Authorization",owner),status);}
    private List<String> ids(JsonNode response){List<String> ids=new ArrayList<>();response.path("nextActivities").forEach(a->ids.add(a.path("activity_id").asText()));return ids;}
    private long issued(JsonNode state,String q){for(JsonNode i:state.path("issued"))if(i.path("question").path("questionId").asText().equals(q))return i.path("question").path("issuedId").asLong();throw new AssertionError(q);}
}
