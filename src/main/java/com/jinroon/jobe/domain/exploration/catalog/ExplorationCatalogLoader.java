package com.jinroon.jobe.domain.exploration.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.scoring.ScoringCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.CatalogEntry;
import com.jinroon.jobe.domain.exploration.catalog.CatalogLoadReport.Outcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색형 진단 카탈로그 적재기(명세 11번 6절, D01~D08).
 *
 * <ol>
 *   <li>datasets/exploration/manifest.json의 파일별 SHA-256과 실제 파일을 대조한다(입력 변조·복사 누락 탐지).</li>
 *   <li>버전 일관성, 수량(187/1,360/32/94), 상태 값, 참조·유일성, 의미 사전 규칙을 모두 검증한다.</li>
 *   <li>하나라도 실패하면 아무것도 쓰지 않는다(fail closed). 통과하면 한 트랜잭션으로 적재한다.</li>
 * </ol>
 *
 * 적재 상태는 입력 파일에 적힌 세 상태(보통 REVIEWED / APPROVED_FOR_IMPLEMENTATION / PENDING)를 그대로 쓰며,
 * ACTIVE 입력은 거부한다. ACTIVE 전환은 검토·출시 게이트를 거치는 별도 작업이다(D05).
 * 같은 (종류, 버전)이 이미 있으면 내용 해시가 같을 때만 건너뛰고, 다르면 거부한다(버전 올림 없이 내용 변경 금지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExplorationCatalogLoader {

    /**
     * 명세 수량(문항 187, 선택지 1,360, 비교 variant 32, 전공 94). 운영 적재는 이 값을 쓰고,
     * 테스트는 가짜 데이터 수량을 넘겨 같은 규칙을 검증한다.
     */
    public record ExpectedCounts(int questions, int options, int variants, int majors) {
        public static final ExpectedCounts SPECIFICATION = new ExpectedCounts(187, 1360, 32, 94);
    }

    private static final Set<String> CONTENT_STATUSES = Set.of("DRAFT", "REVIEWED", "ACTIVE", "ARCHIVED");
    private static final Set<String> IMPLEMENTATION_STATUSES =
            Set.of("PENDING", "APPROVED_FOR_IMPLEMENTATION", "REJECTED", "NOT_APPLICABLE");
    private static final Set<String> DOMAIN_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "NOT_APPLICABLE");
    private static final Set<String> INTEREST_STRUCTURES = Set.of("SINGLE", "PRIMARY", "HYBRID");
    // D03: 비교 세트 정식 표기는 하이픈
    private static final Pattern CANONICAL_COMPARISON_SET = Pattern.compile("^CMP-[A-Z0-9]+(-[A-Z0-9]+)*$");

    private static final String AFFIRMATIVE_INTEREST_RULESET_FILE = "ruleset-v1-6.json";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 검증만 하고 DB에 쓰지 않는다(명세 수량 기준). */
    public CatalogLoadReport validate(Path datasetDir) {
        return validate(datasetDir, ExpectedCounts.SPECIFICATION);
    }

    /** 검증만 하고 DB에 쓰지 않는다. */
    public CatalogLoadReport validate(Path datasetDir, ExpectedCounts expected) {
        Inputs inputs = readAndValidate(datasetDir, expected);
        return new CatalogLoadReport(true, inputs.catalogs().stream()
                .map(c -> new CatalogEntry(c.type(), c.version(), c.contentHash(), Outcome.VALIDATED, c.rowCount()))
                .toList());
    }

    /** 검증 후 적재한다(명세 수량 기준). 검증 실패 시 {@link ExplorationCatalogValidationException}. */
    @Transactional
    public CatalogLoadReport load(Path datasetDir) {
        return load(datasetDir, ExpectedCounts.SPECIFICATION);
    }

    /** 검증 후 적재한다. 검증 실패 시 {@link ExplorationCatalogValidationException}. */
    @Transactional
    public CatalogLoadReport load(Path datasetDir, ExpectedCounts expected) {
        Inputs inputs = readAndValidate(datasetDir, expected);
        List<CatalogEntry> entries = new ArrayList<>();
        List<CatalogSpec> pending = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        for (CatalogSpec spec : inputs.catalogs()) {
            Map<String, Object> existing = findExisting(spec);
            if (existing != null) {
                if (!spec.contentHash().equals(existing.get("content_hash"))) {
                    conflicts.add(spec.type() + " " + spec.version()
                            + ": already loaded with different content (bump the version instead of editing)");
                    continue;
                }
                entries.add(new CatalogEntry(spec.type(), spec.version(), spec.contentHash(), Outcome.ALREADY_LOADED,
                        spec.rowCount()));
            } else {
                pending.add(spec);
                entries.add(new CatalogEntry(spec.type(), spec.version(), spec.contentHash(), Outcome.LOADED,
                        spec.rowCount()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ExplorationCatalogValidationException(conflicts);
        }

        for (CatalogSpec spec : pending) {
            long id = insertCatalog(spec);
            switch (spec.type()) {
                case "QUESTION_CATALOG" -> insertQuestions(id, inputs.questions());
                case "OPTION_CATALOG" -> insertOptions(id, inputs);
                case "MAJOR_MAPPING" -> insertMajors(id, inputs);
                default -> {
                    // OPTION_VARIANT·SEMANTIC_CONTRACT는 카탈로그 행의 metadata_json에, RULESET은 별도 테이블에 저장
                }
            }
        }
        CatalogLoadReport report = new CatalogLoadReport(false, entries);
        log.info("Exploration catalog load finished: {}", entries);
        return report;
    }

    // ------------------------------------------------------------------
    // 읽기·검증
    // ------------------------------------------------------------------

    private Inputs readAndValidate(Path datasetDir, ExpectedCounts expected) {
        List<String> errors = new ArrayList<>();
        Map<String, String> fileHashes = verifyManifest(datasetDir, errors);
        if (!errors.isEmpty()) {
            throw new ExplorationCatalogValidationException(errors);
        }

        JsonNode ruleset = readJson(datasetDir, "ruleset.json");
        JsonNode dictionary = readJson(datasetDir, "semantic-dictionary.json");
        JsonNode majorFile = readJson(datasetDir, "major-mapping.json");
        List<Map<String, String>> questions = readCsv(datasetDir, "questions.csv");
        List<Map<String, String>> options = readCsv(datasetDir, "options.csv");
        List<Map<String, String>> variants = readCsv(datasetDir, "option-variants.csv");
        List<Map<String, String>> setCodes = readCsv(datasetDir, "comparison-set-codes.csv");
        List<Map<String, String>> skipPairs = readCsv(datasetDir, "comparison-skip-pairs.csv");
        List<Map<String, String>> tags = readCsv(datasetDir, "caution-tags.csv");
        List<JsonNode> majors = new ArrayList<>();
        majorFile.path("majors").forEach(majors::add);

        // 버전 일관성(D01): 규칙 JSON의 catalog_versions가 각 파일의 데이터 버전과 맞아야 한다.
        JsonNode versions = ruleset.path("catalog_versions");
        String rulesetVersion = text(ruleset, "ruleset_version");
        String semanticVersion = text(dictionary, "version");
        String majorVersion = text(majorFile, "major_mapping_version");
        requireEquals(errors, "ruleset.semantic_contract_version", text(ruleset, "semantic_contract_version"),
                "semantic-dictionary.version", semanticVersion);
        requireEquals(errors, "ruleset.catalog_versions.major_mapping", text(versions, "major_mapping"),
                "major-mapping.major_mapping_version", majorVersion);
        requireNotBlank(errors, "ruleset.ruleset_version", rulesetVersion);
        requireNotBlank(errors, "ruleset.catalog_versions.question", text(versions, "question"));
        requireNotBlank(errors, "ruleset.catalog_versions.option", text(versions, "option"));
        requireNotBlank(errors, "ruleset.catalog_versions.comparison_option_variant",
                text(versions, "comparison_option_variant"));

        requireCount(errors, "questions", questions.size(), expected.questions());
        requireCount(errors, "options", options.size(), expected.options());
        requireCount(errors, "option variants", variants.size(), expected.variants());
        requireCount(errors, "majors", majors.size(), expected.majors());

        StatusTriple questionStatus = uniformStatus(errors, "questions", questions);
        StatusTriple variantStatus = uniformStatus(errors, "option variants", variants);
        StatusTriple majorStatus = fileStatus(errors, "major-mapping", majorFile);
        StatusTriple rulesetStatus = fileStatus(errors, "ruleset", ruleset);
        StatusTriple semanticStatus = fileStatus(errors, "semantic-dictionary", dictionary);

        validateQuestions(errors, questions, dictionary, ruleset);
        Map<String, String> variantVersionBySet = validateComparison(errors, questions, options, variants, setCodes);
        Set<String> majorCodes = validateMajors(errors, majors, dictionary, variantVersionBySet.keySet());
        validateTags(errors, tags, majorCodes);
        validateSkipPairs(errors, skipPairs, majorCodes, variantVersionBySet.keySet());

        CatalogSpec affirmativeInterestRuleset = null;
        if (fileHashes.containsKey(AFFIRMATIVE_INTEREST_RULESET_FILE)) {
            JsonNode revised = readJson(datasetDir, AFFIRMATIVE_INTEREST_RULESET_FILE);
            String revisedVersion = text(revised, "ruleset_version");
            requireEquals(errors, AFFIRMATIVE_INTEREST_RULESET_FILE, revisedVersion,
                    "current ruleset version", ScoringCatalog.CURRENT_RULESET_VERSION);
            requireEquals(errors, "ruleset.json", rulesetVersion,
                    "legacy ruleset version", ScoringCatalog.LEGACY_RULESET_VERSION);
            if (!versions.equals(revised.path("catalog_versions"))) {
                errors.add(AFFIRMATIVE_INTEREST_RULESET_FILE + ": catalog dependencies differ from ruleset.json");
            }
            try {
                ScoringCatalog.fromSnapshots(revised, majorFile, dictionary);
            } catch (IllegalArgumentException e) {
                errors.add(AFFIRMATIVE_INTEREST_RULESET_FILE + ": " + e.getMessage());
            }
            affirmativeInterestRuleset = new CatalogSpec("RULESET", revisedVersion, fileStatus(errors, "ruleset v1.6", revised),
                    combinedHash(fileHashes, AFFIRMATIVE_INTEREST_RULESET_FILE), null, revised.toString(), 1);
        }

        if (!errors.isEmpty()) {
            throw new ExplorationCatalogValidationException(errors);
        }

        List<CatalogSpec> catalogs = new ArrayList<>(List.of(
                new CatalogSpec("QUESTION_CATALOG", text(versions, "question"), questionStatus,
                        combinedHash(fileHashes, "questions.csv"), null, null, questions.size()),
                // 선택지는 별도 상태 열이 없어 소유 문항 카탈로그의 상태를 따른다(D05).
                new CatalogSpec("OPTION_CATALOG", text(versions, "option"), questionStatus,
                        combinedHash(fileHashes, "options.csv"), null,
                        toJson(Map.of("status_source", "QUESTION_CATALOG")), options.size()),
                new CatalogSpec("OPTION_VARIANT", text(versions, "comparison_option_variant"), variantStatus,
                        combinedHash(fileHashes, "option-variants.csv", "comparison-set-codes.csv",
                                "comparison-skip-pairs.csv"), null,
                        toJson(variantMetadata(variants, setCodes, skipPairs)), variants.size()),
                new CatalogSpec("MAJOR_MAPPING", majorVersion, majorStatus,
                        combinedHash(fileHashes, "major-mapping.json", "caution-tags.csv"),
                        text(majorFile, "schema_version"), null, majors.size()),
                new CatalogSpec("SEMANTIC_CONTRACT", semanticVersion, semanticStatus,
                        combinedHash(fileHashes, "semantic-dictionary.json"), null, dictionary.toString(), 1),
                new CatalogSpec("RULESET", rulesetVersion, rulesetStatus,
                        combinedHash(fileHashes, "ruleset.json"), null, ruleset.toString(), 1)
        ));
        if (affirmativeInterestRuleset != null) catalogs.add(affirmativeInterestRuleset);
        return new Inputs(catalogs, questions, options, variantVersionBySet, majors, tags);
    }

    private Map<String, String> verifyManifest(Path datasetDir, List<String> errors) {
        JsonNode manifest = readJson(datasetDir, "manifest.json");
        Map<String, String> hashes = new TreeMap<>();
        for (JsonNode file : manifest.path("files")) {
            String name = file.path("file").asText();
            String expected = file.path("sha256").asText();
            Path path = datasetDir.resolve(name);
            if (!Files.isRegularFile(path)) {
                errors.add("manifest file missing: " + name);
                continue;
            }
            String actual = sha256Hex(readBytes(path));
            if (!actual.equalsIgnoreCase(expected)) {
                errors.add("sha256 mismatch for " + name + " (manifest " + expected + ", actual " + actual + ")");
            }
            hashes.put(name, actual);
        }
        for (String required : List.of("questions.csv", "options.csv", "option-variants.csv",
                "comparison-set-codes.csv", "comparison-skip-pairs.csv", "major-mapping.json", "caution-tags.csv",
                "ruleset.json", "semantic-dictionary.json")) {
            if (!hashes.containsKey(required)) {
                errors.add("manifest does not list required file: " + required);
            }
        }
        return hashes;
    }

    private void validateQuestions(List<String> errors, List<Map<String, String>> questions, JsonNode dictionary,
                                   JsonNode ruleset) {
        Set<String> seen = new HashSet<>();
        JsonNode moduleRoles = dictionary.path("module_roles");
        JsonNode otherMetrics = dictionary.path("other_module_metrics");
        Map<String, Set<String>> scoringAxes = Map.of(
                "CANDIDATE_TOPIC", stringSet(dictionary.path("interest_axes")),
                "CANDIDATE_ACTIVITY", stringSet(dictionary.path("activity_axes")),
                "CANDIDATE_LEARNING", stringSet(dictionary.path("learning_sets")));
        Set<String> scoreRoles = stringSet(ruleset.path("score_roles"));
        for (Map<String, String> q : questions) {
            String id = q.get("question_id");
            if (!seen.add(id)) {
                errors.add("duplicate question_id " + id);
            }
            String module = q.get("module_code");
            String role = q.get("scoring_role");
            String metric = q.get("metric_code");
            // D08 의미 사전: 모듈의 scoring role이 사전과 같아야 한다.
            if (!moduleRoles.has(module)) {
                errors.add("question " + id + ": unknown module_code " + module);
            } else if (!moduleRoles.path(module).asText().equals(role)) {
                errors.add("question " + id + ": scoring_role " + role + " does not match dictionary role "
                        + moduleRoles.path(module).asText() + " for module " + module);
            }
            // 점수에 들어가는 역할은 해당 축 목록의 지표만 허용
            if (scoreRoles.contains(role)) {
                Set<String> allowed = scoringAxes.get(role);
                if (allowed == null || !allowed.contains(metric)) {
                    errors.add("question " + id + ": scoring metric " + metric + " is not a known axis for " + role);
                }
            } else if (otherMetrics.has(module)) {
                Set<String> allowed = new HashSet<>(Arrays.asList(otherMetrics.path(module).asText().split("\\s+")));
                if (!allowed.contains(metric)) {
                    errors.add("question " + id + ": metric " + metric + " is not defined for module " + module);
                }
            }
        }
    }

    /** 비교 세트 검증(D03) 후 세트별 variant 버전을 돌려준다. */
    private Map<String, String> validateComparison(List<String> errors, List<Map<String, String>> questions,
                                                   List<Map<String, String>> options,
                                                   List<Map<String, String>> variants,
                                                   List<Map<String, String>> setCodes) {
        Set<String> questionIds = new HashSet<>();
        questions.forEach(q -> questionIds.add(q.get("question_id")));

        Map<String, String> variantVersionBySet = new LinkedHashMap<>();
        Map<String, Set<String>> familyBySet = new HashMap<>();
        Map<String, Integer> expectedRowsBySet = new HashMap<>();
        for (Map<String, String> v : variants) {
            String set = v.get("comparison_set");
            if (!CANONICAL_COMPARISON_SET.matcher(set).matches()) {
                errors.add("variant comparison_set is not canonical: " + set);
            }
            if (variantVersionBySet.put(set, v.get("option_variant_version")) != null) {
                errors.add("duplicate variant comparison_set " + set);
            }
            familyBySet.put(set, new HashSet<>(Arrays.asList(v.get("question_family").split("\\|"))));
            expectedRowsBySet.put(set, parseInt(errors, "option_row_count of " + set, v.get("option_row_count")));
        }

        Set<String> canonical = new HashSet<>();
        Set<String> legacy = new HashSet<>();
        for (Map<String, String> code : setCodes) {
            if (!canonical.add(code.get("canonical_comparison_set"))) {
                errors.add("duplicate canonical code " + code.get("canonical_comparison_set"));
            }
            if (!legacy.add(code.get("legacy_comparison_set"))) {
                errors.add("duplicate legacy code " + code.get("legacy_comparison_set"));
            }
        }
        if (!canonical.equals(variantVersionBySet.keySet())) {
            errors.add("comparison code map canonical sets do not match variant manifest sets");
        }

        Set<String> optionKeys = new HashSet<>();
        Map<String, Integer> actualRowsBySet = new HashMap<>();
        for (Map<String, String> o : options) {
            String qid = o.get("question_id");
            String set = blankToNull(o.get("comparison_set"));
            String code = o.get("option_code");
            if (!questionIds.contains(qid)) {
                errors.add("option " + code + " references unknown question " + qid);
            }
            if (code == null || code.isBlank() || !code.equals(code.trim()) || code.length() > 100) {
                errors.add("invalid option_code for question " + qid + ": '" + code + "'");
            }
            if (!optionKeys.add(qid + "|" + Objects.toString(set, "") + "|" + code)) {
                errors.add("duplicate option " + qid + "/" + set + "/" + code);
            }
            if (set != null) {
                if (!variantVersionBySet.containsKey(set)) {
                    errors.add("option " + code + " uses comparison_set " + set + " not in variant manifest");
                } else if (!familyBySet.get(set).contains(qid)) {
                    errors.add("option " + code + " question " + qid + " is not in family of " + set);
                }
                actualRowsBySet.merge(set, 1, Integer::sum);
            }
        }
        expectedRowsBySet.forEach((set, expected) -> {
            int actual = actualRowsBySet.getOrDefault(set, 0);
            if (expected != null && expected != actual) {
                errors.add("comparison_set " + set + " has " + actual + " option rows, manifest says " + expected);
            }
        });
        return variantVersionBySet;
    }

    private Set<String> validateMajors(List<String> errors, List<JsonNode> majors, JsonNode dictionary,
                                       Set<String> comparisonSets) {
        Set<String> groups = stringSet(dictionary.path("groups"));
        Set<String> interestAxes = stringSet(dictionary.path("interest_axes"));
        Set<String> activityAxes = stringSet(dictionary.path("activity_axes"));
        Set<String> learningSets = stringSet(dictionary.path("learning_sets"));
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode m : majors) {
            String code = m.path("major_code").asText();
            if (!codes.add(code)) {
                errors.add("duplicate major_code " + code);
            }
            checkStatuses(errors, "major " + code, m.path("content_status").asText(),
                    m.path("implementation_review_status").asText(), m.path("domain_review_status").asText());
            if (!groups.contains(m.path("exploration_group").asText())) {
                errors.add("major " + code + ": unknown exploration_group " + m.path("exploration_group").asText());
            }
            if (!INTEREST_STRUCTURES.contains(m.path("interest_structure").asText())) {
                errors.add("major " + code + ": unknown interest_structure " + m.path("interest_structure").asText());
            }
            checkAll(errors, "major " + code + " primary_interest_axes", m.path("primary_interest_axes"), interestAxes);
            checkAll(errors, "major " + code + " secondary_interest_axes", m.path("secondary_interest_axes"),
                    interestAxes);
            checkAll(errors, "major " + code + " learning_content_sets", m.path("learning_content_sets"),
                    learningSets);
            checkAll(errors, "major " + code + " comparison_sets", m.path("comparison_sets"), comparisonSets);
            // D04: 서로 다른 활동 축 3개, 배열 순서가 가중치 순서
            List<String> activity = new ArrayList<>();
            m.path("activity_axes").forEach(a -> activity.add(a.asText()));
            if (activity.size() != 3 || new HashSet<>(activity).size() != 3) {
                errors.add("major " + code + ": activity_axes must be 3 distinct axes, got " + activity);
            }
            activity.stream().filter(a -> !activityAxes.contains(a))
                    .forEach(a -> errors.add("major " + code + ": unknown activity axis " + a));
        }
        return codes;
    }

    private void validateTags(List<String> errors, List<Map<String, String>> tags, Set<String> majorCodes) {
        Set<String> keys = new HashSet<>();
        for (Map<String, String> t : tags) {
            String key = t.get("major_code") + "|" + t.get("caution_tag");
            if (!majorCodes.contains(t.get("major_code"))) {
                errors.add("caution tag references unknown major " + t.get("major_code"));
            }
            if (!keys.add(key)) {
                errors.add("duplicate caution tag " + key);
            }
            checkStatuses(errors, "caution tag " + key, t.get("content_status"),
                    t.get("implementation_review_status"), t.get("domain_review_status"));
        }
    }

    private void validateSkipPairs(List<String> errors, List<Map<String, String>> pairs, Set<String> majorCodes,
                                   Set<String> comparisonSets) {
        for (Map<String, String> p : pairs) {
            if (!comparisonSets.contains(p.get("comparison_set"))) {
                errors.add("skip pair uses unknown comparison_set " + p.get("comparison_set"));
            }
            for (String major : List.of(p.get("major_a"), p.get("major_b"))) {
                if (!majorCodes.contains(major)) {
                    errors.add("skip pair references unknown major " + major);
                }
            }
        }
    }

    private StatusTriple uniformStatus(List<String> errors, String label, List<Map<String, String>> rows) {
        Set<StatusTriple> distinct = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            StatusTriple triple = new StatusTriple(row.get("content_status"), row.get("implementation_review_status"),
                    row.get("domain_review_status"));
            checkStatuses(errors, label, triple.content(), triple.implementation(), triple.domain());
            distinct.add(triple);
        }
        if (distinct.size() != 1) {
            errors.add(label + ": rows must share one status triple for the catalog, got " + distinct);
            return new StatusTriple("DRAFT", "PENDING", "PENDING");
        }
        return distinct.iterator().next();
    }

    private StatusTriple fileStatus(List<String> errors, String label, JsonNode file) {
        StatusTriple triple = new StatusTriple(text(file, "content_status"), text(file, "implementation_review_status"),
                text(file, "domain_review_status"));
        checkStatuses(errors, label, triple.content(), triple.implementation(), triple.domain());
        return triple;
    }

    private void checkStatuses(List<String> errors, String label, String content, String implementation,
                               String domain) {
        if (!CONTENT_STATUSES.contains(content)) {
            errors.add(label + ": invalid content_status " + content);
        } else if ("ACTIVE".equals(content)) {
            // ACTIVE 전환은 검토·출시 게이트를 거친 별도 트랜잭션에서만(D05). 적재 입력으로는 받지 않는다.
            errors.add(label + ": content_status ACTIVE cannot be loaded; activation is a separate gated step");
        }
        if (!IMPLEMENTATION_STATUSES.contains(implementation)) {
            errors.add(label + ": invalid implementation_review_status " + implementation);
        }
        if (!DOMAIN_STATUSES.contains(domain)) {
            errors.add(label + ": invalid domain_review_status " + domain);
        }
    }

    // ------------------------------------------------------------------
    // 쓰기
    // ------------------------------------------------------------------

    private Map<String, Object> findExisting(CatalogSpec spec) {
        List<Map<String, Object>> rows = "RULESET".equals(spec.type())
                ? jdbcTemplate.queryForList("SELECT id, content_hash FROM exploration_ruleset WHERE version = ?",
                spec.version())
                : jdbcTemplate.queryForList(
                "SELECT id, content_hash FROM exploration_catalog WHERE catalog_type = ? AND version = ?",
                spec.type(), spec.version());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertCatalog(CatalogSpec spec) {
        StatusTriple s = spec.status();
        if ("RULESET".equals(spec.type())) {
            jdbcTemplate.update("INSERT INTO exploration_ruleset (version, content_status, implementation_review_status, "
                            + "domain_review_status, rules_json, content_hash) VALUES (?, ?, ?, ?, ?, ?)",
                    spec.version(), s.content(), s.implementation(), s.domain(), spec.metadataJson(),
                    spec.contentHash());
            return jdbcTemplate.queryForObject("SELECT id FROM exploration_ruleset WHERE version = ?", Long.class,
                    spec.version());
        }
        jdbcTemplate.update("INSERT INTO exploration_catalog (catalog_type, version, content_status, "
                        + "implementation_review_status, domain_review_status, content_hash, source_schema_version, "
                        + "metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                spec.type(), spec.version(), s.content(), s.implementation(), s.domain(), spec.contentHash(),
                spec.sourceSchemaVersion(), spec.metadataJson());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM exploration_catalog WHERE catalog_type = ? AND version = ?", Long.class,
                spec.type(), spec.version());
    }

    private void insertQuestions(long catalogId, List<Map<String, String>> questions) {
        jdbcTemplate.batchUpdate("INSERT INTO exploration_question (catalog_id, question_id, question_version, stage, "
                        + "module_code, metric_code, content_status, implementation_review_status, "
                        + "domain_review_status, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                questions.stream().map(q -> new Object[]{
                        catalogId, q.get("question_id"), q.get("question_version"), q.get("stage"),
                        q.get("module_code"), blankToNull(q.get("metric_code")), q.get("content_status"),
                        q.get("implementation_review_status"), q.get("domain_review_status"), toJson(q)
                }).toList());
    }

    private void insertOptions(long catalogId, Inputs inputs) {
        List<String> errors = new ArrayList<>();
        List<Object[]> rows = inputs.options().stream().map(o -> {
            String set = blankToNull(o.get("comparison_set"));
            return new Object[]{
                    catalogId, o.get("question_id"), set, set == null ? null : inputs.variantVersionBySet().get(set),
                    o.get("option_code"), parseInt(errors, "option_order of " + o.get("option_code"),
                    o.get("option_order")), toJson(o)
            };
        }).toList();
        if (!errors.isEmpty()) {
            throw new ExplorationCatalogValidationException(errors);
        }
        jdbcTemplate.batchUpdate("INSERT INTO exploration_option (catalog_id, question_id, comparison_set, "
                + "variant_version, option_code, option_order, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
    }

    private void insertMajors(long catalogId, Inputs inputs) {
        // major_id는 운영 majors.id와 MAJOR_ROW_*·이름·source_file 3중 대조 후 연결한다(11번 명세 5절). 지금은 NULL.
        jdbcTemplate.batchUpdate("INSERT INTO exploration_major_mapping (catalog_id, major_code, major_id, mapping_json, "
                        + "content_status, implementation_review_status, domain_review_status) "
                        + "VALUES (?, ?, NULL, ?, ?, ?, ?)",
                inputs.majors().stream().map(m -> new Object[]{
                        catalogId, m.path("major_code").asText(), m.toString(), m.path("content_status").asText(),
                        m.path("implementation_review_status").asText(), m.path("domain_review_status").asText()
                }).toList());
        Map<String, Long> mappingIds = new HashMap<>();
        jdbcTemplate.query("SELECT id, major_code FROM exploration_major_mapping WHERE catalog_id = ?",
                rs -> {
                    mappingIds.put(rs.getString("major_code"), rs.getLong("id"));
                }, catalogId);
        // 미제안(NOT_PROPOSED) 조합은 감사 기록이라 태그로 적재하지 않는다(11번 명세 6절 4항).
        jdbcTemplate.batchUpdate("INSERT INTO exploration_caution_tag (mapping_id, tag_code, proposal_status, "
                        + "legacy_review_status, content_status, implementation_review_status, domain_review_status, "
                        + "evidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                inputs.tags().stream()
                        .filter(t -> "PROPOSED".equals(t.get("proposal_status")))
                        .map(t -> new Object[]{
                                mappingIds.get(t.get("major_code")), t.get("caution_tag"), t.get("proposal_status"),
                                blankToNull(t.get("legacy_review_status")), t.get("content_status"),
                                t.get("implementation_review_status"), t.get("domain_review_status"),
                                blankToNull(t.get("evidence"))
                        }).toList());
    }

    private Map<String, Object> variantMetadata(List<Map<String, String>> variants, List<Map<String, String>> setCodes,
                                                List<Map<String, String>> skipPairs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("variants", variants);
        metadata.put("legacy_code_map", setCodes);
        metadata.put("same_direction_skip_pairs", skipPairs);
        return metadata;
    }

    // ------------------------------------------------------------------
    // 유틸
    // ------------------------------------------------------------------

    /** 여러 파일로 된 카탈로그의 해시: 파일명 순으로 "이름:sha256" 줄을 이어 다시 SHA-256. 파일 하나면 그 파일 해시. */
    private static String combinedHash(Map<String, String> fileHashes, String... files) {
        if (files.length == 1) {
            return fileHashes.get(files[0]);
        }
        StringBuilder joined = new StringBuilder();
        Arrays.stream(files).sorted().forEach(f -> joined.append(f).append(':').append(fileHashes.get(f)).append('\n'));
        return sha256Hex(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode readJson(Path dir, String name) {
        try {
            return objectMapper.readTree(stripBom(new String(readBytes(dir.resolve(name)), StandardCharsets.UTF_8)));
        } catch (JsonProcessingException exception) {
            throw new ExplorationCatalogValidationException(List.of(name + " is not valid JSON: "
                    + exception.getOriginalMessage()));
        }
    }

    private static List<Map<String, String>> readCsv(Path dir, String name) {
        try {
            return CsvReader.read(new String(readBytes(dir.resolve(name)), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new ExplorationCatalogValidationException(List.of(name + ": " + exception.getMessage()));
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read " + path, exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize catalog row", exception);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String stripBom(String content) {
        return content.startsWith("﻿") ? content.substring(1) : content;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static void checkAll(List<String> errors, String label, JsonNode array, Set<String> allowed) {
        array.forEach(v -> {
            if (!allowed.contains(v.asText())) {
                errors.add(label + ": unknown value " + v.asText());
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Integer parseInt(List<String> errors, String label, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            errors.add(label + " is not an integer: " + value);
            return null;
        }
    }

    private static void requireEquals(List<String> errors, String leftLabel, String left, String rightLabel,
                                      String right) {
        if (left == null || !left.equals(right)) {
            errors.add(leftLabel + " (" + left + ") does not match " + rightLabel + " (" + right + ")");
        }
    }

    private static void requireNotBlank(List<String> errors, String label, String value) {
        if (value == null || value.isBlank()) {
            errors.add(label + " is missing");
        }
    }

    private static void requireCount(List<String> errors, String label, int actual, int expected) {
        if (actual != expected) {
            errors.add(label + " count is " + actual + ", expected " + expected);
        }
    }

    record StatusTriple(String content, String implementation, String domain) {
    }

    record CatalogSpec(String type, String version, StatusTriple status, String contentHash,
                       String sourceSchemaVersion, String metadataJson, int rowCount) {
    }

    record Inputs(List<CatalogSpec> catalogs, List<Map<String, String>> questions, List<Map<String, String>> options,
                  Map<String, String> variantVersionBySet, List<JsonNode> majors, List<Map<String, String>> tags) {
    }
}
