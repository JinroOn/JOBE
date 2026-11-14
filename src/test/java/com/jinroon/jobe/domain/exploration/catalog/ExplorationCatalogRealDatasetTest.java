package com.jinroon.jobe.domain.exploration.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.CatalogEntry;
import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 카탈로그(datasets/exploration)를 명세 수량으로 검증·적재한다.
 * 문항·규칙 원본은 비공개라 저장소에 없으므로, 원본을 받아 둔 PC에서만 실행되고 CI에서는 건너뛴다.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다. 적재한 데이터는 테스트 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest
@Transactional
@EnabledIf("realDatasetPresent")
class ExplorationCatalogRealDatasetTest {

    private static final Path DATASET = Path.of("datasets/exploration");

    @Autowired
    private ExplorationCatalogLoader loader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static boolean realDatasetPresent() {
        return Files.isRegularFile(DATASET.resolve("questions.csv"));
    }

    @Test
    void validatesWithSpecificationCountsAndVersions() {
        CatalogLoadReport report = loader.validate(DATASET);

        Map<String, String> versions = report.catalogs().stream()
                .collect(Collectors.toMap(CatalogEntry::catalogType, CatalogEntry::version));
        assertThat(versions).containsExactlyInAnyOrderEntriesOf(Map.of(
                "QUESTION_CATALOG", "QUESTION_CATALOG_V1_1",
                "OPTION_CATALOG", "OPTION_CATALOG_V1_0",
                "OPTION_VARIANT", "OPTION_VARIANT_V1_0",
                "MAJOR_MAPPING", "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE",
                "SEMANTIC_CONTRACT", "SEMANTIC_CONTRACT_V1_0",
                "RULESET", "RULESET_V1_4_IMPLEMENTATION_CANDIDATE"));
    }

    @Test
    void loadsWithSpecificationCounts() {
        CatalogLoadReport report = loader.load(DATASET);

        // 개발 DB에 이미 적재돼 있을 수 있으므로 LOADED 또는 ALREADY_LOADED
        assertThat(report.catalogs()).allMatch(c -> c.outcome() == Outcome.LOADED
                || c.outcome() == Outcome.ALREADY_LOADED);
        assertThat(rows("exploration_question", "QUESTION_CATALOG", "QUESTION_CATALOG_V1_1")).isEqualTo(187);
        assertThat(rows("exploration_option", "OPTION_CATALOG", "OPTION_CATALOG_V1_0")).isEqualTo(1360);
        assertThat(rows("exploration_major_mapping", "MAJOR_MAPPING", "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE"))
                .isEqualTo(94);
        Integer tags = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exploration_caution_tag t "
                + "JOIN exploration_major_mapping m ON t.mapping_id = m.id JOIN exploration_catalog c ON m.catalog_id = c.id "
                + "WHERE c.catalog_type = 'MAJOR_MAPPING' AND c.version = 'MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE'",
                Integer.class);
        assertThat(tags).isEqualTo(187); // 내부 승인 139 + 외부 검토 대기 48
    }

    private int rows(String table, String type, String version) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " t JOIN exploration_catalog c "
                + "ON t.catalog_id = c.id WHERE c.catalog_type = ? AND c.version = ?", Integer.class, type, version);
    }
}
