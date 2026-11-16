package com.jinroon.jobe.domain.exploration.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 카탈로그 목록·상세(#101). 검토·활성화 대상 선택용이며 조회만 한다.
 * 실행 설정 버전을 테스트 전용 이름으로 바꾸고, 대상 행은 테스트 전용 버전명과 트랜잭션 롤백을 쓴다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다.
 */
@SpringBootTest(properties = {
        "exploration.runtime.ruleset-version=TEST_QUERY_RULESET_IN_USE",
        "exploration.activities.catalog-version=TEST_QUERY_ACTIVITY_IN_USE"})
@AutoConfigureMockMvc
@Transactional
class AdminCatalogQueryApiTest {

    private static final String API = "/api/admin/exploration/catalogs";
    private static final String HASH = "f".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;

    private User reviewer;
    private User activator;
    private long questionCatalogId;

    @BeforeEach
    void prepare() {
        reviewer = saveUser(UserRole.admin);
        activator = saveUser(UserRole.admin);
        String version = "TEST_QUERY_Q_" + suffix();
        jdbc.update("INSERT INTO exploration_catalog(catalog_type,version,content_status,implementation_review_status,"
                + "domain_review_status,content_hash) VALUES ('QUESTION_CATALOG',?,'DRAFT','PENDING','PENDING',?)", version, HASH);
        questionCatalogId = jdbc.queryForObject("SELECT id FROM exploration_catalog WHERE version=?", Long.class, version);
        for (String question : new String[] {"TQ-02", "TQ-01"}) {
            jdbc.update("INSERT INTO exploration_question(catalog_id,question_id,question_version,stage,module_code,metric_code,"
                    + "content_status,implementation_review_status,domain_review_status,metadata_json) "
                    + "VALUES (?,?,'1.0','STAGE_1','STATE',NULL,'REVIEWED','APPROVED_FOR_IMPLEMENTATION','PENDING',?)",
                    questionCatalogId, question, "{\"question_text\":\"테스트 문항 " + question + "\"}");
        }
    }

    @Test
    void memberCannotReadCatalogs() throws Exception {
        String member = bearer(saveUser(UserRole.member));
        mockMvc.perform(get(API).header("Authorization", member)).andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/CATALOG/" + questionCatalogId).header("Authorization", member))
                .andExpect(status().isForbidden());
    }

    @Test
    void listShowsStatusesHashItemCountAndActionsForDraft() throws Exception {
        JsonNode row = find(json(get(API), activator), "CATALOG", questionCatalogId);
        assertThat(row.path("catalogType").asText()).isEqualTo("QUESTION_CATALOG");
        assertThat(row.path("contentStatus").asText()).isEqualTo("DRAFT");
        assertThat(row.path("implementationReviewStatus").asText()).isEqualTo("PENDING");
        assertThat(row.path("domainReviewStatus").asText()).isEqualTo("PENDING");
        assertThat(row.path("contentHash").asText()).isEqualTo(HASH);
        assertThat(row.path("itemCount").asInt()).isEqualTo(2);
        assertThat(row.path("usedByRuntime").asBoolean()).isFalse();
        assertThat(texts(row.path("allowedActions"))).containsExactly("REVIEW", "ARCHIVE");
        assertThat(row.path("blockedReasons").path("ACTIVATE").asText()).isEqualTo("CATALOG_ACTIVATION_NOT_ALLOWED");
    }

    @Test
    void activationIsAllowedOnlyForAnAdminWhoDidNotApprove() throws Exception {
        review("CATALOG", questionCatalogId, "IMPLEMENTATION");
        review("CATALOG", questionCatalogId, "DOMAIN");

        JsonNode forActivator = find(json(get(API), activator), "CATALOG", questionCatalogId);
        assertThat(forActivator.path("contentStatus").asText()).isEqualTo("REVIEWED");
        assertThat(texts(forActivator.path("allowedActions"))).containsExactly("REVIEW", "ACTIVATE", "ARCHIVE");

        JsonNode forReviewer = find(json(get(API), reviewer), "CATALOG", questionCatalogId);
        assertThat(texts(forReviewer.path("allowedActions"))).containsExactly("REVIEW", "ARCHIVE");
        assertThat(forReviewer.path("blockedReasons").path("ACTIVATE").asText()).isEqualTo("CATALOG_SELF_APPROVAL");
    }

    @Test
    void runtimeAndArchivedTargetsShowWhyActionsAreBlocked() throws Exception {
        jdbc.update("INSERT INTO exploration_ruleset(version,content_status,implementation_review_status,domain_review_status,"
                + "rules_json,content_hash) VALUES ('TEST_QUERY_RULESET_IN_USE','REVIEWED','APPROVED_FOR_IMPLEMENTATION','PENDING','{}',?)", HASH);
        long inUse = jdbc.queryForObject("SELECT id FROM exploration_ruleset WHERE version='TEST_QUERY_RULESET_IN_USE'", Long.class);
        jdbc.update("UPDATE exploration_catalog SET content_status='ARCHIVED' WHERE id=?", questionCatalogId);

        JsonNode list = json(get(API), activator);
        JsonNode runtime = find(list, "RULESET", inUse);
        assertThat(runtime.path("catalogType").asText()).isEqualTo("RULESET");
        assertThat(runtime.path("usedByRuntime").asBoolean()).isTrue();
        assertThat(runtime.path("itemCount").isNull()).isTrue();
        assertThat(runtime.path("blockedReasons").path("ARCHIVE").asText()).isEqualTo("CATALOG_IN_USE");

        JsonNode archived = find(list, "CATALOG", questionCatalogId);
        assertThat(archived.path("allowedActions").isEmpty()).isTrue();
        assertThat(archived.path("blockedReasons").path("REVIEW").asText()).isEqualTo("CATALOG_REVIEW_ARCHIVED");
        assertThat(archived.path("blockedReasons").path("ARCHIVE").asText()).isEqualTo("CATALOG_ALREADY_ARCHIVED");
    }

    @Test
    void filtersNarrowTheListAndInvalidFiltersAreRejected() throws Exception {
        jdbc.update("INSERT INTO exploration_activity_catalog(version,major_mapping_version,content_hash,payload_json,"
                + "content_status,implementation_review_status,domain_review_status) VALUES (?,?,?,'{}','DRAFT','PENDING','PENDING')",
                "TEST_QUERY_A_" + suffix(), "TEST_MAPPING", HASH);

        JsonNode activityOnly = json(get(API).param("targetType", "ACTIVITY_CATALOG"), activator);
        for (JsonNode row : activityOnly) assertThat(row.path("targetType").asText()).isEqualTo("ACTIVITY_CATALOG");
        assertThat(activityOnly.size()).isPositive();

        JsonNode drafts = json(get(API).param("contentStatus", "DRAFT"), activator);
        for (JsonNode row : drafts) assertThat(row.path("contentStatus").asText()).isEqualTo("DRAFT");
        find(drafts, "CATALOG", questionCatalogId);

        mockMvc.perform(get(API).param("targetType", "ACTIVITY").header("Authorization", bearer(activator)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(API).param("contentStatus", "PUBLISHED").header("Authorization", bearer(activator)))
                .andExpect(status().isBadRequest());
        // 파라미터 제약 위반은 500이 아니라 400이다(검토·활성화 이력 조회의 targetId도 같다).
        mockMvc.perform(get(API + "/CATALOG/-1").header("Authorization", bearer(activator)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/exploration/reviews").param("targetType", "CATALOG").param("targetId", "-1")
                .header("Authorization", bearer(activator))).andExpect(status().isBadRequest());
    }

    @Test
    void detailIncludesQuestionsReviewsAndActivations() throws Exception {
        review("CATALOG", questionCatalogId, "IMPLEMENTATION");
        review("CATALOG", questionCatalogId, "DOMAIN");
        mockMvc.perform(post("/api/admin/exploration/activations").header("Authorization", bearer(activator))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"CATALOG\",\"targetId\":" + questionCatalogId + ",\"scope\":\"DEV\",\"reason\":\"테스트\"}"))
                .andExpect(status().isOk());

        JsonNode detail = json(get(API + "/CATALOG/" + questionCatalogId), activator);
        assertThat(detail.path("catalog").path("contentStatus").asText()).isEqualTo("ACTIVE");
        assertThat(detail.path("reviews").size()).isEqualTo(2);
        assertThat(detail.path("activations").size()).isEqualTo(1);
        assertThat(detail.path("questions").size()).isEqualTo(2);
        assertThat(detail.path("questions").get(0).path("questionId").asText()).isEqualTo("TQ-01");
        assertThat(detail.path("questions").get(0).path("text").asText()).isEqualTo("테스트 문항 TQ-01");

        mockMvc.perform(get(API + "/CATALOG/999999999").header("Authorization", bearer(activator)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(API + "/ACTIVITY/1").header("Authorization", bearer(activator)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readingDoesNotChangeAnything() throws Exception {
        int reviewsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exploration_review_history", Integer.class);
        int activationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exploration_catalog_activation", Integer.class);
        json(get(API), activator);
        json(get(API + "/CATALOG/" + questionCatalogId), activator);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exploration_review_history", Integer.class)).isEqualTo(reviewsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exploration_catalog_activation", Integer.class)).isEqualTo(activationsBefore);
        assertThat(jdbc.queryForObject("SELECT content_status FROM exploration_catalog WHERE id=?", String.class, questionCatalogId))
                .isEqualTo("DRAFT");
    }

    private JsonNode json(MockHttpServletRequestBuilder request, User admin) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    private JsonNode find(JsonNode rows, String type, long id) {
        for (JsonNode row : rows) {
            if (row.path("targetType").asText().equals(type) && row.path("targetId").asLong() == id) return row;
        }
        throw new AssertionError("목록에 대상이 없습니다: " + type + " " + id);
    }

    private java.util.List<String> texts(JsonNode array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private void review(String type, long id, String kind) throws Exception {
        mockMvc.perform(post("/api/admin/exploration/reviews").header("Authorization", bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"" + type + "\",\"targetId\":" + id + ",\"reviewKind\":\"" + kind
                                + "\",\"decision\":\"APPROVED\",\"evidenceReference\":\"docs/review/test.md\"}"))
                .andExpect(status().isCreated());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private User saveUser(UserRole role) {
        User user = User.registerEmailUser("catalog-query-" + UUID.randomUUID() + "@example.com",
                "pbkdf2$1$c2FsdA==$aGFzaA==", "카탈로그조회", null);
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createToken(user);
    }
}
