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
 * ACTIVE 전환·보관(3.2.2 카탈로그 승인·활성화 연계의 2단계).
 * 실행 설정의 버전을 테스트 전용 이름으로 바꿔 '사용 중' 판단을 실제 적재 자료와 분리한다.
 * 대상 행은 테스트 전용 버전명으로 만들고 트랜잭션 롤백으로 지운다. 로컬 MySQL(docker compose up -d mysql)이 필요하다.
 */
@SpringBootTest(properties = {
        "exploration.runtime.ruleset-version=TEST_ACT_RULESET_IN_USE",
        "exploration.activities.catalog-version=TEST_ACT_ACTIVITY_IN_USE"})
@AutoConfigureMockMvc
@Transactional
class AdminCatalogActivationApiTest {

    private static final String API = "/api/admin/exploration/activations";
    private static final String HASH = "d".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;

    private User reviewer;
    private User activator;

    @BeforeEach
    void prepare() {
        reviewer = saveUser(UserRole.admin);
        activator = saveUser(UserRole.admin);
    }

    @Test
    void memberCannotActivateArchiveOrReadHistory() throws Exception {
        long id = ruleset("DRAFT");
        String member = bearer(saveUser(UserRole.member));
        mockMvc.perform(post(API).header("Authorization", member).contentType(MediaType.APPLICATION_JSON)
                .content(activateBody("RULESET", id, "DEV"))).andExpect(status().isForbidden());
        mockMvc.perform(post(API + "/archive").header("Authorization", member).contentType(MediaType.APPLICATION_JSON)
                .content(archiveBody("RULESET", id))).andExpect(status().isForbidden());
        mockMvc.perform(get(API).header("Authorization", member).param("targetType", "RULESET")
                .param("targetId", String.valueOf(id))).andExpect(status().isForbidden());
    }

    @Test
    void reviewedTargetWithBothApprovalsBecomesActiveAndIsLogged() throws Exception {
        long id = ruleset("DRAFT");
        approve("RULESET", id, "IMPLEMENTATION");
        approve("RULESET", id, "DOMAIN");

        activate(activator, "RULESET", id, "DEV")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.contentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.activation.action").value("ACTIVATE"))
                .andExpect(jsonPath("$.activation.scope").value("DEV"))
                .andExpect(jsonPath("$.activation.actorId").value(activator.getId()))
                .andExpect(jsonPath("$.activation.targetHash").value(HASH))
                .andExpect(jsonPath("$.activation.reviewIds.length()").value(2))
                .andExpect(jsonPath("$.archived.length()").value(0));

        mockMvc.perform(get(API).header("Authorization", bearer(activator)).param("targetType", "RULESET")
                        .param("targetId", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.contentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.activations.length()").value(1));
    }

    @Test
    void reviewerCannotActivateWhatTheyApproved() throws Exception {
        long id = ruleset("DRAFT");
        approve("RULESET", id, "IMPLEMENTATION");
        approve("RULESET", id, "DOMAIN");
        activate(reviewer, "RULESET", id, "DEV")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_SELF_APPROVAL"));
        assertThat(contentStatus("exploration_ruleset", id)).isEqualTo("REVIEWED");
        assertThat(activationCount("RULESET", id)).isZero();
    }

    @Test
    void draftOrChangedContentCannotBeActivated() throws Exception {
        long id = ruleset("DRAFT");
        approve("RULESET", id, "IMPLEMENTATION");
        activate(activator, "RULESET", id, "DEV")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_ACTIVATION_NOT_ALLOWED"));

        approve("RULESET", id, "DOMAIN");
        // 승인 뒤 내용이 바뀌면(hash 변경) 이전 승인은 현재 내용의 승인이 아니다.
        jdbc.update("UPDATE exploration_ruleset SET content_hash=? WHERE id=?", "e".repeat(64), id);
        activate(activator, "RULESET", id, "DEV")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_APPROVAL_MISSING"));
        assertThat(contentStatus("exploration_ruleset", id)).isEqualTo("REVIEWED");
    }

    @Test
    void widerScopesNeedPilotAndReleaseApprovals() throws Exception {
        long id = ruleset("DRAFT");
        approve("RULESET", id, "IMPLEMENTATION");
        approve("RULESET", id, "DOMAIN");
        activate(activator, "RULESET", id, "PILOT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_APPROVAL_MISSING"));

        approve("RULESET", id, "PILOT");
        activate(activator, "RULESET", id, "PUBLIC")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_APPROVAL_MISSING"));

        approve("RULESET", id, "RELEASE");
        activate(activator, "RULESET", id, "PUBLIC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activation.scope").value("PUBLIC"))
                .andExpect(jsonPath("$.activation.reviewIds.length()").value(4));
    }

    @Test
    void newActiveCatalogArchivesPreviousOneOfSameTypeAtomically() throws Exception {
        long first = catalog("QUESTION_CATALOG", "TEST_ACT_Q_" + suffix());
        long second = catalog("QUESTION_CATALOG", "TEST_ACT_Q_" + suffix());
        long otherType = catalog("OPTION_CATALOG", "TEST_ACT_O_" + suffix());
        for (long id : new long[] {first, second, otherType}) {
            approve("CATALOG", id, "IMPLEMENTATION");
            approve("CATALOG", id, "DOMAIN");
        }
        activate(activator, "CATALOG", first, "DEV").andExpect(status().isOk());
        activate(activator, "CATALOG", otherType, "DEV").andExpect(status().isOk());

        activate(activator, "CATALOG", second, "DEV")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.contentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.activation.relatedTargetId").value(first))
                .andExpect(jsonPath("$.archived.length()").value(1))
                .andExpect(jsonPath("$.archived[0].targetId").value(first))
                .andExpect(jsonPath("$.archived[0].contentStatus").value("ARCHIVED"));

        assertThat(contentStatus("exploration_catalog", first)).isEqualTo("ARCHIVED");
        assertThat(contentStatus("exploration_catalog", otherType)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT related_target_id FROM exploration_catalog_activation WHERE target_type='CATALOG' "
                + "AND target_id=? AND action='ARCHIVE'", Long.class, first)).isEqualTo(second);
    }

    @Test
    void replacementIsRefusedWhenPreviousActiveIsUsedByRuntimeSettings() throws Exception {
        long inUse = ruleset("ACTIVE", "TEST_ACT_RULESET_IN_USE");
        long next = ruleset("DRAFT");
        approve("RULESET", next, "IMPLEMENTATION");
        approve("RULESET", next, "DOMAIN");
        activate(activator, "RULESET", next, "DEV")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_IN_USE"));
        assertThat(contentStatus("exploration_ruleset", inUse)).isEqualTo("ACTIVE");
        assertThat(contentStatus("exploration_ruleset", next)).isEqualTo("REVIEWED");
    }

    @Test
    void runtimeCatalogInUseCannotBeArchivedButUnusedOneCan() throws Exception {
        String questionVersion = "TEST_ACT_Q_IN_USE_" + suffix();
        jdbc.update("INSERT INTO exploration_ruleset(version,content_status,implementation_review_status,domain_review_status,"
                + "rules_json,content_hash) VALUES ('TEST_ACT_RULESET_IN_USE','REVIEWED','APPROVED_FOR_IMPLEMENTATION','PENDING',?,?)",
                "{\"catalog_versions\":{\"question\":\"" + questionVersion + "\"}}", HASH);
        long inUse = catalog("QUESTION_CATALOG", questionVersion);
        long unused = catalog("QUESTION_CATALOG", "TEST_ACT_Q_" + suffix());

        archive("CATALOG", inUse)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_IN_USE"));
        assertThat(contentStatus("exploration_catalog", inUse)).isEqualTo("DRAFT");

        archive("CATALOG", unused)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activation.action").value("ARCHIVE"))
                .andExpect(jsonPath("$.target.contentStatus").value("ARCHIVED"));
        archive("CATALOG", unused)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_ALREADY_ARCHIVED"));
        // 보관된 자료는 다시 검토하거나 활성화할 수 없다.
        review(reviewer, "CATALOG", unused, "IMPLEMENTATION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_REVIEW_ARCHIVED"));
        activate(activator, "CATALOG", unused, "DEV")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_ACTIVATION_NOT_ALLOWED"));
    }

    @Test
    void activityCatalogInUseCanBeWithdrawn() throws Exception {
        jdbc.update("INSERT INTO exploration_activity_catalog(version,major_mapping_version,content_hash,payload_json,"
                + "content_status,implementation_review_status,domain_review_status) "
                + "VALUES ('TEST_ACT_ACTIVITY_IN_USE','TEST_MAPPING',?,'{}','ACTIVE','APPROVED_FOR_IMPLEMENTATION','APPROVED')", HASH);
        long id = jdbc.queryForObject("SELECT id FROM exploration_activity_catalog WHERE version='TEST_ACT_ACTIVITY_IN_USE'", Long.class);
        archive("ACTIVITY_CATALOG", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target.contentStatus").value("ARCHIVED"));
    }

    @Test
    void invalidRequestsChangeNothing() throws Exception {
        long id = ruleset("DRAFT");
        activate(activator, "ACTIVITY", id, "DEV").andExpect(status().isBadRequest());
        activate(activator, "RULESET", 999_999_999L, "DEV").andExpect(status().isNotFound());
        mockMvc.perform(post(API).header("Authorization", bearer(activator)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"RULESET\",\"targetId\":" + id + ",\"scope\":\"DEV\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(API).header("Authorization", bearer(activator)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"RULESET\",\"targetId\":" + id + ",\"scope\":\"STAGING\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        assertThat(activationCount("RULESET", id)).isZero();
        assertThat(contentStatus("exploration_ruleset", id)).isEqualTo("DRAFT");
    }

    private long ruleset(String contentStatus) {
        return ruleset(contentStatus, "TEST_ACT_R_" + suffix());
    }

    private long ruleset(String contentStatus, String version) {
        jdbc.update("INSERT INTO exploration_ruleset(version,content_status,implementation_review_status,domain_review_status,"
                + "rules_json,content_hash) VALUES (?,?,'PENDING','PENDING','{}',?)", version, contentStatus, HASH);
        return jdbc.queryForObject("SELECT id FROM exploration_ruleset WHERE version=?", Long.class, version);
    }

    private long catalog(String type, String version) {
        jdbc.update("INSERT INTO exploration_catalog(catalog_type,version,content_status,implementation_review_status,"
                + "domain_review_status,content_hash) VALUES (?,?,'DRAFT','PENDING','PENDING',?)", type, version, HASH);
        return jdbc.queryForObject("SELECT id FROM exploration_catalog WHERE catalog_type=? AND version=?", Long.class, type, version);
    }

    private void approve(String type, long id, String kind) throws Exception {
        review(reviewer, type, id, kind).andExpect(status().isCreated());
    }

    private ResultActions review(User admin, String type, long id, String kind) throws Exception {
        return mockMvc.perform(post("/api/admin/exploration/reviews").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"" + type + "\",\"targetId\":" + id + ",\"reviewKind\":\"" + kind
                        + "\",\"decision\":\"APPROVED\",\"evidenceReference\":\"docs/review/test.md\"}"));
    }

    private ResultActions activate(User admin, String type, long id, String scope) throws Exception {
        return mockMvc.perform(post(API).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(activateBody(type, id, scope)));
    }

    private ResultActions archive(String type, long id) throws Exception {
        return mockMvc.perform(post(API + "/archive").header("Authorization", bearer(activator))
                .contentType(MediaType.APPLICATION_JSON).content(archiveBody(type, id)));
    }

    private String activateBody(String type, long id, String scope) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":" + id + ",\"scope\":\"" + scope + "\",\"reason\":\"테스트 활성화\"}";
    }

    private String archiveBody(String type, long id) {
        return "{\"targetType\":\"" + type + "\",\"targetId\":" + id + ",\"reason\":\"테스트 철회\"}";
    }

    private String contentStatus(String table, long id) {
        return jdbc.queryForObject("SELECT content_status FROM " + table + " WHERE id=?", String.class, id);
    }

    private int activationCount(String type, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exploration_catalog_activation WHERE target_type=? AND target_id=?",
                Integer.class, type, id);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private User saveUser(UserRole role) {
        User user = User.registerEmailUser("activation-test-" + UUID.randomUUID() + "@example.com",
                "pbkdf2$1$c2FsdA==$aGFzaA==", "활성화테스트", null);
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createToken(user);
    }
}
