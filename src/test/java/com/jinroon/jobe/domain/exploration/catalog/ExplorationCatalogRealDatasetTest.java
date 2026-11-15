package com.jinroon.jobe.domain.exploration.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.CatalogEntry;
import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 카탈로그(datasets/exploration)를 명세 수량으로 검증·적재한다.
 * 원본은 저장소에 함께 커밋돼 있어 CI에서도 실행된다. 원본 폴더를 제외한 checkout에서는 건너뛴다.
 * MySQL(로컬은 docker compose up -d mysql)이 필요하다. 적재한 데이터는 테스트 트랜잭션 롤백으로 지운다.
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

    @Autowired
    private ObjectMapper objectMapper;

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
                "MAJOR_MAPPING", "MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE",
                "SEMANTIC_CONTRACT", "SEMANTIC_CONTRACT_V1_0",
                "RULESET", "RULESET_V1_5_IMPLEMENTATION_CANDIDATE"));
    }

    @Test
    void loadsWithSpecificationCounts() {
        CatalogLoadReport report = loader.load(DATASET);

        // 개발 DB에 이미 적재돼 있을 수 있으므로 LOADED 또는 ALREADY_LOADED
        assertThat(report.catalogs()).allMatch(c -> c.outcome() == Outcome.LOADED
                || c.outcome() == Outcome.ALREADY_LOADED);
        assertThat(rows("exploration_question", "QUESTION_CATALOG", "QUESTION_CATALOG_V1_1")).isEqualTo(187);
        assertThat(rows("exploration_option", "OPTION_CATALOG", "OPTION_CATALOG_V1_0")).isEqualTo(1360);
        assertThat(rows("exploration_major_mapping", "MAJOR_MAPPING", "MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE"))
                .isEqualTo(94);
        Integer tags = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exploration_caution_tag t "
                + "JOIN exploration_major_mapping m ON t.mapping_id = m.id JOIN exploration_catalog c ON m.catalog_id = c.id "
                + "WHERE c.catalog_type = 'MAJOR_MAPPING' AND c.version = 'MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE'",
                Integer.class);
        assertThat(tags).isEqualTo(187); // 내부 승인 139 + 외부 검토 대기 48
        assertMajorDisplayName("MAJOR_ROW_6", "문예창작학과", "문예창작과");
        assertMajorDisplayName("MAJOR_ROW_93", "유아교육학과", "유아교육과");
    }

    private void assertMajorDisplayName(String code, String expectedName, String previousName) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(m.mapping_json, '$.major_name')) AS display_name, "
                        + "JSON_UNQUOTE(JSON_EXTRACT(m.mapping_json, '$.major_name_aliases[0]')) AS previous_name "
                        + "FROM exploration_major_mapping m JOIN exploration_catalog c ON m.catalog_id = c.id "
                        + "WHERE c.catalog_type = 'MAJOR_MAPPING' AND c.version = ? AND m.major_code = ?",
                "MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE", code);
        assertThat(row.get("display_name")).isEqualTo(expectedName);
        assertThat(row.get("previous_name")).isEqualTo(previousName);
    }

    @Test
    void revisedNamesLoadAlongsidePreviousVersionsWithoutOverwritingThem(@TempDir Path previous)
            throws IOException, NoSuchAlgorithmException {
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readString(DATASET.resolve("manifest.json")));
        for (var file : manifest.path("files")) {
            String name = file.path("file").asText();
            String text = Files.readString(DATASET.resolve(name), StandardCharsets.UTF_8);
            if (name.equals("major-mapping.json")) {
                text = text.replace("MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE", "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE")
                        .replace("문예창작학과", "문예창작과").replace("유아교육학과", "유아교육과");
            } else if (name.equals("caution-tags.csv")) {
                text = text.replace("문예창작학과", "문예창작과").replace("유아교육학과", "유아교육과");
            } else if (name.equals("ruleset.json")) {
                text = text.replace("RULESET_V1_5_IMPLEMENTATION_CANDIDATE", "RULESET_V1_4_IMPLEMENTATION_CANDIDATE")
                        .replace("MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE", "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE");
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Files.write(previous.resolve(name), bytes);
            ((ObjectNode) file).put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            ((ObjectNode) file).put("bytes", bytes.length);
        }
        Files.writeString(previous.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
        loader.load(previous);
        String oldHash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM exploration_catalog WHERE catalog_type='MAJOR_MAPPING' AND version=?",
                String.class, "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE");

        loader.load(DATASET);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_hash FROM exploration_catalog WHERE catalog_type='MAJOR_MAPPING' AND version=?",
                String.class, "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE")).isEqualTo(oldHash);
        assertThat(rows("exploration_major_mapping", "MAJOR_MAPPING", "MAJOR_MAPPING_V1_1_IMPLEMENTATION_CANDIDATE"))
                .isEqualTo(94);
        assertMajorDisplayName("MAJOR_ROW_6", "문예창작학과", "문예창작과");
        assertMajorDisplayName("MAJOR_ROW_93", "유아교육학과", "유아교육과");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exploration_ruleset WHERE version IN (?, ?)", Integer.class,
                "RULESET_V1_4_IMPLEMENTATION_CANDIDATE", "RULESET_V1_5_IMPLEMENTATION_CANDIDATE")).isEqualTo(2);
    }

    private int rows(String table, String type, String version) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " t JOIN exploration_catalog c "
                + "ON t.catalog_id = c.id WHERE c.catalog_type = ? AND c.version = ?", Integer.class, type, version);
    }
}
