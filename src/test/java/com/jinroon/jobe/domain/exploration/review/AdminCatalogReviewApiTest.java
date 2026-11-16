package com.jinroon.jobe.domain.exploration.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.security.JwtProvider;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 검토 기록 API(3.2.2 카탈로그 승인·활성화 연계의 1단계).
 * 검토 대상 행은 테스트 전용 버전명으로 만들고 트랜잭션 롤백으로 지운다. 실제 적재 자료는 건드리지 않는다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminCatalogReviewApiTest {

    private static final String API = "/api/admin/exploration/reviews";
    private static final String HASH = "a".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;

    private User admin;
    private long activityCatalogId;
    private long activityId;

    @BeforeEach
    void prepare() {
        admin = saveUser(UserRole.admin);
        String version = "TEST_REVIEW_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO exploration_activity_catalog(version,major_mapping_version,content_hash,payload_json,"
                + "content_status,implementation_review_status,domain_review_status) VALUES (?,?,?,'{}','DRAFT','PENDING','PENDING')",
                version, "TEST_MAPPING", HASH);
        activityCatalogId = jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version=?", Long.class, version);
        jdbc.update("INSERT INTO exploration_activity(catalog_id,activity_code,version,major_code,content_hash,payload_json,"
                + "content_status,implementation_review_status,domain_review_status) VALUES (?,?,?,NULL,?,'{}','DRAFT','PENDING','PENDING')",
                activityCatalogId, "TEST-ACT-1", "TEST-1", "b".repeat(64));
        activityId = jdbc.queryForObject("SELECT id FROM exploration_activity WHERE catalog_id=?", Long.class, activityCatalogId);
    }

    @Test
    void anonymousAndMemberCannotRecordOrReadReviews() throws Exception {
        String body = body("ACTIVITY", activityId, "IMPLEMENTATION", "APPROVED");
        mockMvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String member = bearer(saveUser(UserRole.member));
        mockMvc.perform(post(API).header("Authorization", member).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(API).header("Authorization", member).param("targetType", "ACTIVITY")
                .param("targetId", String.valueOf(activityId))).andExpect(status().isForbidden());
        assertThat(reviewCount("ACTIVITY", activityId)).isZero();
    }

    @Test
    void approvalsAreRecordedByServerAndMoveDraftToReviewed() throws Exception {
        record("ACTIVITY", activityId, "IMPLEMENTATION", "APPROVED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.review.decision").value("APPROVED_FOR_IMPLEMENTATION"))
                .andExpect(jsonPath("$.review.scope").value("DEV"))
                .andExpect(jsonPath("$.review.reviewerId").value(admin.getId()))
                .andExpect(jsonPath("$.review.targetHash").value("b".repeat(64)))
                .andExpect(jsonPath("$.review.matchesCurrentContent").value(true))
                .andExpect(jsonPath("$.target.implementationReviewStatus").value("APPROVED_FOR_IMPLEMENTATION"))
                .andExpect(jsonPath("$.target.contentStatus").value("DRAFT"));

        record("ACTIVITY", activityId, "DOMAIN", "APPROVED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.review.decision").value("APPROVED"))
                .andExpect(jsonPath("$.target.domainReviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.target.contentStatus").value("REVIEWED"));

        // 활동 추천이 요구하는 조건(대상 hash 일치, DEV 범위, 근거, 검토자, 결정 값)을 그대로 만족해야 한다.
        Map<String, Object> latest = jdbc.queryForMap("SELECT * FROM exploration_review_history WHERE target_type='ACTIVITY' "
                + "AND target_id=? AND review_kind='DOMAIN' ORDER BY reviewed_at DESC,id DESC LIMIT 1", activityId);
        assertThat(latest.get("target_version")).isEqualTo("TEST-1");
        assertThat(latest.get("target_hash")).isEqualTo("b".repeat(64));
        assertThat(latest.get("scope")).isEqualTo("DEV");
        assertThat(latest.get("evidence_reference")).isEqualTo("docs/review/test.md");
    }

    @Test
    void laterRejectionWinsAndReturnsReviewedToDraftWithoutDeletingHistory() throws Exception {
        record("ACTIVITY_CATALOG", activityCatalogId, "IMPLEMENTATION", "APPROVED").andExpect(status().isCreated());
        record("ACTIVITY_CATALOG", activityCatalogId, "DOMAIN", "APPROVED")
                .andExpect(jsonPath("$.target.contentStatus").value("REVIEWED"));

        record("ACTIVITY_CATALOG", activityCatalogId, "DOMAIN", "REJECTED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.review.decision").value("REJECTED"))
                .andExpect(jsonPath("$.target.domainReviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.target.contentStatus").value("DRAFT"));

        assertThat(reviewCount("ACTIVITY_CATALOG", activityCatalogId)).isEqualTo(3);
        mockMvc.perform(get(API).header("Authorization", bearer(admin)).param("targetType", "ACTIVITY_CATALOG")
                        .param("targetId", String.valueOf(activityCatalogId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.contentStatus").value("DRAFT"))
                .andExpect(jsonPath("$.reviews.length()").value(3))
                .andExpect(jsonPath("$.reviews[0].decision").value("REJECTED"));
    }

    @Test
    void clientCannotOverrideReviewerHashScopeOrTime() throws Exception {
        String body = "{\"targetType\":\"ACTIVITY\",\"targetId\":" + activityId + ",\"reviewKind\":\"IMPLEMENTATION\","
                + "\"decision\":\"APPROVED\",\"evidenceReference\":\"docs/review/test.md\",\"reviewerId\":999999,"
                + "\"targetHash\":\"" + "c".repeat(64) + "\",\"scope\":\"PUBLIC\",\"reviewedAt\":\"2099-01-01T00:00:00\"}";
        mockMvc.perform(post(API).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.review.reviewerId").value(admin.getId()))
                .andExpect(jsonPath("$.review.targetHash").value("b".repeat(64)))
                .andExpect(jsonPath("$.review.scope").value("DEV"));
        // 검토 시각은 서버 시각이다. 요청의 2099년 값은 무시된다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exploration_review_history WHERE target_type='ACTIVITY' "
                + "AND target_id=? AND YEAR(reviewed_at) >= 2099", Integer.class, activityId)).isZero();
    }

    @Test
    void pilotAndReleaseNeedDevelopmentApprovalFirstAndDoNotChangeStatusColumns() throws Exception {
        record("ACTIVITY", activityId, "PILOT", "APPROVED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_REVIEW_PREREQUISITE"));
        assertThat(reviewCount("ACTIVITY", activityId)).isZero();

        record("ACTIVITY", activityId, "IMPLEMENTATION", "APPROVED");
        record("ACTIVITY", activityId, "DOMAIN", "APPROVED");
        record("ACTIVITY", activityId, "PILOT", "APPROVED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.review.scope").value("PILOT"))
                .andExpect(jsonPath("$.target.contentStatus").value("REVIEWED"))
                .andExpect(jsonPath("$.target.domainReviewStatus").value("APPROVED"));
        record("ACTIVITY", activityId, "RELEASE", "REJECTED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.review.scope").value("PUBLIC"))
                .andExpect(jsonPath("$.target.contentStatus").value("REVIEWED"));
    }

    @Test
    void archivedTargetCannotBeReviewed() throws Exception {
        jdbc.update("UPDATE exploration_activity SET content_status='ARCHIVED' WHERE id=?", activityId);
        record("ACTIVITY", activityId, "DOMAIN", "APPROVED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_REVIEW_ARCHIVED"));
        assertThat(reviewCount("ACTIVITY", activityId)).isZero();
    }

    @Test
    void activeTargetKeepsActiveStatusWhenRejected() throws Exception {
        jdbc.update("UPDATE exploration_activity SET content_status='ACTIVE',implementation_review_status='APPROVED_FOR_IMPLEMENTATION',"
                + "domain_review_status='APPROVED' WHERE id=?", activityId);
        record("ACTIVITY", activityId, "DOMAIN", "REJECTED")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.target.contentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.target.domainReviewStatus").value("REJECTED"));
    }

    @Test
    void invalidRequestsAreRejectedWithoutHistory() throws Exception {
        record("ACTIVITY", 999_999_999L, "DOMAIN", "APPROVED").andExpect(status().isNotFound());
        send("{\"targetType\":\"ACTIVITY\",\"targetId\":" + activityId + ",\"reviewKind\":\"DOMAIN\",\"decision\":\"APPROVED\","
                + "\"evidenceReference\":\"  \"}").andExpect(status().isBadRequest());
        send("{\"targetType\":\"UNKNOWN\",\"targetId\":" + activityId + ",\"reviewKind\":\"DOMAIN\",\"decision\":\"APPROVED\","
                + "\"evidenceReference\":\"docs/review/test.md\"}").andExpect(status().isBadRequest());
        send("{\"targetType\":\"ACTIVITY\",\"targetId\":" + activityId + ",\"reviewKind\":\"DOMAIN\",\"decision\":\"APPROVED_FOR_IMPLEMENTATION\","
                + "\"evidenceReference\":\"docs/review/test.md\"}").andExpect(status().isBadRequest());
        mockMvc.perform(get(API).header("Authorization", bearer(admin)).param("targetType", "NOPE").param("targetId", "1"))
                .andExpect(status().isBadRequest());
        assertThat(reviewCount("ACTIVITY", activityId)).isZero();
    }

    @Test
    void rulesetAndCatalogTargetsUseTheirOwnTables() throws Exception {
        String version = "TEST_REVIEW_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO exploration_ruleset(version,content_status,implementation_review_status,domain_review_status,"
                + "rules_json,content_hash) VALUES (?,'DRAFT','PENDING','PENDING','{}',?)", version, HASH);
        long rulesetId = jdbc.queryForObject("SELECT id FROM exploration_ruleset WHERE version=?", Long.class, version);
        jdbc.update("INSERT INTO exploration_catalog(catalog_type,version,content_status,implementation_review_status,"
                + "domain_review_status,content_hash) VALUES ('QUESTION_CATALOG',?,'DRAFT','PENDING','PENDING',?)", version, HASH);
        long catalogId = jdbc.queryForObject("SELECT id FROM exploration_catalog WHERE version=?", Long.class, version);

        record("RULESET", rulesetId, "IMPLEMENTATION", "APPROVED")
                .andExpect(jsonPath("$.target.targetType").value("RULESET"))
                .andExpect(jsonPath("$.target.implementationReviewStatus").value("APPROVED_FOR_IMPLEMENTATION"));
        record("CATALOG", catalogId, "IMPLEMENTATION", "REJECTED")
                .andExpect(jsonPath("$.target.targetType").value("CATALOG"))
                .andExpect(jsonPath("$.target.implementationReviewStatus").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT implementation_review_status FROM exploration_ruleset WHERE id=?", String.class, rulesetId))
                .isEqualTo("APPROVED_FOR_IMPLEMENTATION");
        assertThat(jdbc.queryForObject("SELECT implementation_review_status FROM exploration_catalog WHERE id=?", String.class, catalogId))
                .isEqualTo("REJECTED");
    }

    private ResultActions record(String type, long id, String kind, String decision) throws Exception {
        return send(body(type, id, kind, decision));
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(API)
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String type, long id, String kind, String decision) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":" + id + ",\"reviewKind\":\"" + kind + "\",\"decision\":\""
                + decision + "\",\"evidenceReference\":\"docs/review/test.md\"}";
    }

    private int reviewCount(String type, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exploration_review_history WHERE target_type=? AND target_id=?",
                Integer.class, type, id);
    }

    private User saveUser(UserRole role) {
        User user = User.registerEmailUser("review-test-" + UUID.randomUUID() + "@example.com",
                "pbkdf2$1$c2FsdA==$aGFzaA==", "검토테스트", null);
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createToken(user);
    }
}
