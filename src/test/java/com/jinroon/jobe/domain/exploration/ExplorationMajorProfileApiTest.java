package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 전공 프로필(#101 "전공 자세히 보기"). 공통 지원 클래스가 실제 카탈로그를 적재하고,
 * OWNER는 DEV 허용 사용자, OUTSIDE는 허용 목록 밖이다.
 * 공유 매핑 행을 바꾸는 테스트는 끝날 때 반드시 원래 값으로 되돌린다.
 */
class ExplorationMajorProfileApiTest extends ExplorationApiTestSupport {

    private static final String PROFILE = "/api/exploration/v1/majors/%s/profile";
    private static final String MAPPING = "MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE";

    @Test
    void profileIsBuiltFromTheMappedServiceFileWithoutScores() throws Exception {
        JsonNode profile = profile("MAJOR_ROW_10", owner, 200);
        assertThat(profile.path("majorCode").asText()).isEqualTo("MAJOR_ROW_10");
        assertThat(profile.path("name").asText()).isEqualTo("언어학과");
        assertThat(profile.path("category").asText()).isEqualTo("인문계열");
        assertThat(profile.path("description").asText()).startsWith("언어학과는");
        assertThat(texts(profile.path("keywords"))).contains("언어 구조");
        assertThat(profile.path("bestFor").asText()).isNotBlank();
        assertThat(profile.path("comparisonNote").asText()).isNotBlank();
        assertThat(texts(profile.path("lessCentral"))).isNotEmpty();
        assertThat(texts(profile.path("relatedCareers"))).contains("번역가");
        assertThat(profile.path("profileVersion").asText()).isEqualTo("major-service-excel-v1");
        assertThat(profile.path("source").asText()).isEqualTo("커리어넷 대학교 학과정보");
        assertThat(profile.path("mappingVersion").asText()).isEqualTo(MAPPING);
        assertThat(profile.path("domainReviewStatus").asText()).isEqualTo("PENDING");
        assertThat(profile.path("testOnly").asBoolean()).isTrue();
        // 추천 계산용 값은 노출하지 않는다.
        String body = profile.toString();
        assertThat(body).doesNotContain("serviceCompetencyScores", "serviceTendencyScores", "coreAxes", "weight");
    }

    @Test
    void everyMappedMajorHasAProfile() throws Exception {
        List<String> codes = jdbc.queryForList("SELECT m.major_code FROM exploration_major_mapping m JOIN exploration_catalog c "
                + "ON c.id=m.catalog_id WHERE c.catalog_type='MAJOR_MAPPING' AND c.version=?", String.class, MAPPING);
        assertThat(codes).hasSize(94);
        for (String code : codes) {
            JsonNode profile = profile(code, owner, 200);
            assertThat(profile.path("name").asText()).as(code).isNotBlank();
            assertThat(profile.path("description").asText()).as(code).isNotBlank();
        }
    }

    @Test
    void laterAddedMajorsInTheOlderFileFormatAreReadToo() throws Exception {
        JsonNode profile = profile("MAJOR_ROW_1001", owner, 200);
        assertThat(profile.path("name").asText()).isEqualTo("소프트웨어학과");
        assertThat(texts(profile.path("relatedCareers"))).contains("소프트웨어개발자");
        assertThat(texts(profile.path("keywords"))).contains("서비스 구현");
        // 이 형식에는 데이터 버전·출처 기록이 없다. 없는 값을 만들어 넣지 않는다.
        assertThat(profile.path("profileVersion").isNull()).isTrue();
        assertThat(profile.path("source").isNull()).isTrue();
    }

    @Test
    void unknownOrMalformedCodesAreRejected() throws Exception {
        assertThat(profile("MAJOR_ROW_999999", owner, 404).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(profile("NOT_A_CODE", owner, 400).path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void onlyUsersInsideTheExplorationScopeCanRead() throws Exception {
        mvc.perform(get(PROFILE.formatted("MAJOR_ROW_10"))).andExpect(status().isUnauthorized());
        assertThat(profile("MAJOR_ROW_10", outside, 503).path("code").asText()).isEqualTo("NO_ACTIVE_CATALOG");
    }

    @Test
    void unapprovedOrMismatchedMappingIsNotExposed() throws Exception {
        long id = jdbc.queryForObject("SELECT m.id FROM exploration_major_mapping m JOIN exploration_catalog c ON c.id=m.catalog_id "
                + "WHERE c.catalog_type='MAJOR_MAPPING' AND c.version=? AND m.major_code='MAJOR_ROW_10'", Long.class, MAPPING);
        String status = jdbc.queryForObject("SELECT implementation_review_status FROM exploration_major_mapping WHERE id=?", String.class, id);
        String json = jdbc.queryForObject("SELECT mapping_json FROM exploration_major_mapping WHERE id=?", String.class, id);
        try {
            jdbc.update("UPDATE exploration_major_mapping SET implementation_review_status='PENDING' WHERE id=?", id);
            assertThat(profile("MAJOR_ROW_10", owner, 404).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");

            jdbc.update("UPDATE exploration_major_mapping SET implementation_review_status=?,"
                    + "mapping_json=JSON_SET(mapping_json,'$.major_name','다른학과') WHERE id=?", status, id);
            assertThat(profile("MAJOR_ROW_10", owner, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");

            jdbc.update("UPDATE exploration_major_mapping SET mapping_json=JSON_SET(mapping_json,'$.major_name','언어학과',"
                    + "'$.source_file','../../etc/passwd') WHERE id=?", id);
            assertThat(profile("MAJOR_ROW_10", owner, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        } finally {
            jdbc.update("UPDATE exploration_major_mapping SET implementation_review_status=?, mapping_json=? WHERE id=?", status, json, id);
        }
        profile("MAJOR_ROW_10", owner, 200);
    }

    private JsonNode profile(String code, String token, int status) throws Exception {
        return response(get(PROFILE.formatted(code)).header("Authorization", token), status);
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
