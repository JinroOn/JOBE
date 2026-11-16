package com.jinroon.jobe.domain.exploration.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.CatalogEntry;
import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.Outcome;
import com.jinroon.jobe.domain.exploration.catalog.ExplorationCatalogLoader.ExpectedCounts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카탈로그 적재기 규칙 테스트. 실제 문항·규칙은 비공개라 저장소에 없으므로
 * src/test/resources/exploration-fixture의 가짜 데이터로 같은 검증·적재 규칙을 확인한다(CI 포함).
 * 실제 데이터 확인은 ExplorationCatalogRealDatasetTest(데이터가 있을 때만 실행).
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다. 적재한 데이터는 테스트 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest
@Transactional
class ExplorationCatalogLoaderTest {

    static final Path FIXTURE = Path.of("src/test/resources/exploration-fixture");
    static final ExpectedCounts FIXTURE_COUNTS = new ExpectedCounts(6, 17, 1, 2);

    @Autowired
    private ExplorationCatalogLoader loader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    // ---- 정상 입력 ----

    @Test
    void validatesFixtureWithoutWriting() {
        int questionsBefore = count("exploration_question");

        CatalogLoadReport report = loader.validate(FIXTURE, FIXTURE_COUNTS);

        assertThat(report.dryRun()).isTrue();
        assertThat(versionsByType(report)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "QUESTION_CATALOG", "FIXTURE_QUESTION_CATALOG_V1_0",
                "OPTION_CATALOG", "FIXTURE_OPTION_CATALOG_V1_0",
                "OPTION_VARIANT", "FIXTURE_OPTION_VARIANT_V1_0",
                "MAJOR_MAPPING", "FIXTURE_MAJOR_MAPPING_V1_0",
                "SEMANTIC_CONTRACT", "FIXTURE_SEMANTIC_V1_0",
                "RULESET", "FIXTURE_RULESET_V1_0"));
        assertThat(report.catalogs()).allMatch(c -> c.outcome() == Outcome.VALIDATED);
        assertThat(count("exploration_question")).isEqualTo(questionsBefore);
    }

    @Test
    void loadsFixtureWithCountsStatusesAndVariants() {
        CatalogLoadReport report = loader.load(FIXTURE, FIXTURE_COUNTS);

        assertThat(report.catalogs()).allMatch(c -> c.outcome() == Outcome.LOADED);
        long questionCatalog = catalogId("QUESTION_CATALOG", "FIXTURE_QUESTION_CATALOG_V1_0");
        long optionCatalog = catalogId("OPTION_CATALOG", "FIXTURE_OPTION_CATALOG_V1_0");
        long majorCatalog = catalogId("MAJOR_MAPPING", "FIXTURE_MAJOR_MAPPING_V1_0");

        assertThat(countWhere("exploration_question", "catalog_id = ?", questionCatalog)).isEqualTo(6);
        assertThat(countWhere("exploration_option", "catalog_id = ?", optionCatalog)).isEqualTo(17);
        // 비교 선택지에는 매니페스트의 variant 버전, 일반 선택지에는 NULL
        assertThat(countWhere("exploration_option",
                "catalog_id = ? AND comparison_set = 'CMP-AB' AND variant_version = '1.0'", optionCatalog)).isEqualTo(3);
        assertThat(countWhere("exploration_option",
                "catalog_id = ? AND comparison_set IS NULL AND variant_version IS NULL", optionCatalog)).isEqualTo(14);
        assertThat(countWhere("exploration_major_mapping", "catalog_id = ? AND major_id IS NULL", majorCatalog))
                .isEqualTo(2);
        // 주의 태그: 제안된 2개만 적재(승인 1 + 검토 대기 1), 미제안 1개는 적재하지 않음
        assertThat(tagCount(majorCatalog, "1 = 1")).isEqualTo(2);
        assertThat(tagCount(majorCatalog, "t.content_status = 'REVIEWED'")).isEqualTo(1);
        assertThat(tagCount(majorCatalog, "t.content_status = 'DRAFT'")).isEqualTo(1);
        // 적재는 ACTIVE를 만들지 않는다(D05)
        assertThat(countWhere("exploration_catalog", "id IN (?, ?, ?) AND content_status <> 'REVIEWED'",
                questionCatalog, optionCatalog, majorCatalog)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT source_schema_version FROM exploration_catalog WHERE id = ?",
                String.class, majorCatalog)).isEqualTo("1.2");
        assertThat(countWhere("exploration_ruleset", "version = 'FIXTURE_RULESET_V1_0'")).isEqualTo(1);
    }

    @Test
    void secondLoadOfSameContentIsSkipped() {
        loader.load(FIXTURE, FIXTURE_COUNTS);
        int optionsBefore = count("exploration_option");

        CatalogLoadReport second = loader.load(FIXTURE, FIXTURE_COUNTS);

        assertThat(second.catalogs()).allMatch(c -> c.outcome() == Outcome.ALREADY_LOADED);
        assertThat(count("exploration_option")).isEqualTo(optionsBefore);
    }

    @Test
    void changedContentUnderSameVersionIsRejected() throws IOException {
        loader.load(FIXTURE, FIXTURE_COUNTS);
        Path changed = copyWith("questions.csv", s -> s.replace(": INT-A-01\"", ": INT-A-01 (수정)\""));

        assertThatThrownBy(() -> loader.load(changed, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("QUESTION_CATALOG FIXTURE_QUESTION_CATALOG_V1_0: already loaded with different content");
    }

    // ---- 망가뜨린 입력 ----

    @Test
    void fileChangedWithoutManifestUpdateIsRejected() throws IOException {
        Path dir = copyFixture();
        Files.writeString(dir.resolve("ruleset.json"), Files.readString(dir.resolve("ruleset.json")) + " ");

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("sha256 mismatch for ruleset.json");
    }

    @Test
    void missingFileIsRejected() throws IOException {
        Path dir = copyFixture();
        Files.delete(dir.resolve("options.csv"));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("manifest file missing: options.csv");
    }

    @Test
    void wrongCountIsRejected() {
        assertThatThrownBy(() -> loader.validate(FIXTURE, new ExpectedCounts(7, 17, 1, 2)))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("questions count is 6, expected 7");
    }

    @Test
    void realSpecificationCountsRejectFixture() {
        // 운영 기본값(187/1,360/32/94)으로는 가짜 데이터가 통과하지 않는다.
        assertThatThrownBy(() -> loader.validate(FIXTURE))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("expected 187");
    }

    @Test
    void unknownScoringMetricIsRejected() throws IOException {
        Path dir = copyWith("questions.csv", s -> s.replaceFirst("\"INT_A\"", "\"INT_AX\""));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("scoring metric INT_AX is not a known axis");
    }

    @Test
    void roleNotMatchingDictionaryIsRejected() throws IOException {
        Path dir = copyWith("questions.csv", s -> s.replaceFirst("\"ROUTING\"", "\"CANDIDATE_TOPIC\""));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("does not match dictionary role ROUTING for module STATE");
    }

    @Test
    void activeStatusInInputIsRejected() throws IOException {
        Path dir = copyWithJson("ruleset.json", node -> ((ObjectNode) node).put("content_status", "ACTIVE"));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("content_status ACTIVE cannot be loaded");
    }

    @Test
    void semanticVersionMismatchIsRejected() throws IOException {
        Path dir = copyWithJson("semantic-dictionary.json",
                node -> ((ObjectNode) node).put("version", "FIXTURE_SEMANTIC_V9_9"));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("ruleset.semantic_contract_version");
    }

    @Test
    void duplicateActivityAxisIsRejected() throws IOException {
        Path dir = copyWithJson("major-mapping.json", node -> {
            ArrayNode axes = (ArrayNode) node.path("majors").get(0).path("activity_axes");
            axes.set(1, axes.get(0));
        });

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("activity_axes must be 3 distinct axes");
    }

    @Test
    void nonCanonicalComparisonSetIsRejected() throws IOException {
        Path dir = copyWith("option-variants.csv", s -> s.replaceFirst("\"CMP-AB\"", "\"CMP_AB\""));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("not canonical: CMP_AB");
    }

    @Test
    void comparisonOptionOutsideFamilyIsRejected() throws IOException {
        Path dir = copyWith("options.csv", s -> s.replaceFirst("\"CMP-AB-01\",\"CMP_AB__A\"", "\"INT-A-01\",\"CMP_AB__A\""));

        assertThatThrownBy(() -> loader.validate(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class)
                .hasMessageContaining("is not in family of CMP-AB");
    }

    @Test
    void failedValidationWritesNothing() throws IOException {
        Path dir = copyWith("questions.csv", s -> s.replaceFirst("\"INT_A\"", "\"INT_AX\""));
        int catalogsBefore = count("exploration_catalog");
        int questionsBefore = count("exploration_question");

        assertThatThrownBy(() -> loader.load(dir, FIXTURE_COUNTS))
                .isInstanceOf(ExplorationCatalogValidationException.class);
        assertThat(count("exploration_catalog")).isEqualTo(catalogsBefore);
        assertThat(count("exploration_question")).isEqualTo(questionsBefore);
    }

    // ---- helpers ----

    private Path copyFixture() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("dataset"));
        try (Stream<Path> files = Files.list(FIXTURE)) {
            for (Path file : files.toList()) {
                Files.copy(file, dir.resolve(file.getFileName()));
            }
        }
        return dir;
    }

    private Path copyWith(String file, UnaryOperator<String> edit) throws IOException {
        Path dir = copyFixture();
        String original = Files.readString(dir.resolve(file));
        String edited = edit.apply(original);
        assertThat(edited).as("test edit must change " + file).isNotEqualTo(original);
        Files.writeString(dir.resolve(file), edited);
        refreshManifestHash(dir, file);
        return dir;
    }

    private Path copyWithJson(String file, Consumer<JsonNode> edit) throws IOException {
        Path dir = copyFixture();
        JsonNode node = objectMapper.readTree(Files.readString(dir.resolve(file)));
        edit.accept(node);
        Files.writeString(dir.resolve(file), objectMapper.writeValueAsString(node));
        refreshManifestHash(dir, file);
        return dir;
    }

    /** 내용 검증 규칙만 확인하려고, 바꾼 파일의 manifest 해시를 새 값으로 맞춘다. */
    private void refreshManifestHash(Path dir, String file) throws IOException {
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readString(dir.resolve("manifest.json")));
        for (JsonNode entry : manifest.path("files")) {
            if (file.equals(entry.path("file").asText())) {
                ((ObjectNode) entry).put("sha256",
                        ExplorationCatalogLoader.sha256Hex(Files.readAllBytes(dir.resolve(file))));
            }
        }
        Files.writeString(dir.resolve("manifest.json"), objectMapper.writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    private Map<String, String> versionsByType(CatalogLoadReport report) {
        return report.catalogs().stream().collect(Collectors.toMap(CatalogEntry::catalogType, CatalogEntry::version));
    }

    private long catalogId(String type, String version) {
        return jdbcTemplate.queryForObject("SELECT id FROM exploration_catalog WHERE catalog_type = ? AND version = ?",
                Long.class, type, version);
    }

    private int tagCount(long majorCatalog, String where) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exploration_caution_tag t "
                + "JOIN exploration_major_mapping m ON t.mapping_id = m.id WHERE m.catalog_id = ? AND " + where,
                Integer.class, majorCatalog);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String where, Object... args) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }
}
