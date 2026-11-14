package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flyway V1(탐색형 카탈로그 테이블) 적용과 MySQL 제약 동작을 확인한다.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다. 테스트 데이터는 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest
@Transactional
class ExplorationCatalogMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedV1() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);

        assertThat(versions).contains("1");
    }

    @Test
    void catalogTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name LIKE 'exploration\\_%'", String.class);

        assertThat(tables).contains(
                "exploration_catalog",
                "exploration_question",
                "exploration_option",
                "exploration_ruleset",
                "exploration_major_mapping",
                "exploration_caution_tag",
                "exploration_review_history"
        );
    }

    @Test
    void optionUniqueKeyTreatsNullComparisonSetAsSameValue() {
        long catalogId = insertCatalog("OPTION_CATALOG", "TEST_OPTION_CATALOG");
        insertOption(catalogId, "INT-HUM-01", null, null, "AGREE");

        // MySQL UNIQUE는 NULL끼리 중복을 허용하지만, COALESCE 생성 열로 정규화해 막는다(D07).
        assertThatThrownBy(() -> insertOption(catalogId, "INT-HUM-01", null, null, "AGREE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optionAllowsSameCodeInDifferentComparisonSets() {
        long catalogId = insertCatalog("OPTION_CATALOG", "TEST_OPTION_CATALOG");
        insertOption(catalogId, "CMP-SPS-01", "CMP-LANGUAGE", "1.0", "CMP_LANGUAGE__UNSURE");
        insertOption(catalogId, "CMP-SPS-01", "CMP-ART-EDU", "1.0", "CMP_LANGUAGE__UNSURE");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exploration_option WHERE catalog_id = ?", Integer.class, catalogId);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void optionRejectsComparisonSetWithoutVariant() {
        long catalogId = insertCatalog("OPTION_CATALOG", "TEST_OPTION_CATALOG");

        // MySQL CHECK 위반(3819)은 Spring이 DataIntegrityViolationException으로 변환하지 않으므로 제약 이름으로 확인한다.
        assertThatThrownBy(() -> insertOption(catalogId, "CMP-SPS-01", "CMP-LANGUAGE", null, "X"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_exploration_option_variant_pair");
    }

    @Test
    void catalogRejectsUnknownStatus() {
        // 검토 상태 값(APPROVED)을 수명주기 필드에 넣는 실수를 막는다(D05).
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO exploration_catalog (catalog_type, version, content_status, implementation_review_status, "
                        + "domain_review_status, content_hash) VALUES ('RULESET', 'X', 'APPROVED', "
                        + "'PENDING', 'PENDING', ?)", "0".repeat(64)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_exploration_catalog_content");
    }

    @Test
    void catalogVersionIsUniquePerType() {
        insertCatalog("RULESET", "RULESET_V1_4_IMPLEMENTATION_CANDIDATE");

        assertThatThrownBy(() -> insertCatalog("RULESET", "RULESET_V1_4_IMPLEMENTATION_CANDIDATE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertCatalog(String type, String version) {
        jdbcTemplate.update(
                "INSERT INTO exploration_catalog (catalog_type, version, content_status, implementation_review_status, "
                        + "domain_review_status, content_hash) VALUES (?, ?, 'REVIEWED', 'APPROVED_FOR_IMPLEMENTATION', "
                        + "'PENDING', ?)", type, version, "0".repeat(64));
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertOption(long catalogId, String questionId, String comparisonSet, String variant, String code) {
        jdbcTemplate.update(
                "INSERT INTO exploration_option (catalog_id, question_id, comparison_set, variant_version, option_code, "
                        + "option_order, metadata_json) VALUES (?, ?, ?, ?, ?, 1, JSON_OBJECT())",
                catalogId, questionId, comparisonSet, variant, code);
    }
}
