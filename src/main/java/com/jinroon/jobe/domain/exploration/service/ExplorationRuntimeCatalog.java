package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.display.ComparisonQuestionDisplayCatalog;
import com.jinroon.jobe.domain.exploration.display.ResultDisplayCatalog;
import com.jinroon.jobe.domain.exploration.entity.ExplorationSession.VersionBundle;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** DB catalog adapter. An existing session is always evaluated using its immutable frozen payload. */
@Component
@RequiredArgsConstructor
public class ExplorationRuntimeCatalog {
    private final JdbcTemplate jdbc;
    private final ExplorationJson json;
    private final ResultDisplayCatalog displayCatalog;
    private final ComparisonQuestionDisplayCatalog comparisonQuestionDisplay;
    @Value("${exploration.runtime.ruleset-version:RULESET_V1_5_IMPLEMENTATION_CANDIDATE}")
    private String rulesetVersion;

    public record Frozen(VersionBundle ids, String hash, JsonNode payload, BranchCatalog branch) {}

    public Frozen selectForDevelopment() {
        List<Map<String, Object>> roots = jdbc.queryForList("SELECT * FROM exploration_ruleset WHERE version=?", rulesetVersion);
        if (roots.isEmpty()) throw new ExplorationApiException(503, "NO_ACTIVE_CATALOG", "적재된 실행 규칙이 없습니다.");
        Map<String, Object> rule = roots.get(0);
        reviewed(rule);
        JsonNode rules = json.read(rule.get("rules_json").toString());
        ObjectNode payload = json.object();
        payload.set("rules", rules);
        ObjectNode versions = payload.putObject("versions");
        ObjectNode hashes = payload.putObject("sourceHashes");
        versions.put("ruleset", rulesetVersion);
        hashes.put("ruleset", rule.get("content_hash").toString());
        Map<String, Map<String, Object>> catalogs = new HashMap<>();
        Map<String, String> requested = Map.of(
                "QUESTION_CATALOG", rules.path("catalog_versions").path("question").asText(),
                "OPTION_CATALOG", rules.path("catalog_versions").path("option").asText(),
                "OPTION_VARIANT", rules.path("catalog_versions").path("comparison_option_variant").asText(),
                "MAJOR_MAPPING", rules.path("catalog_versions").path("major_mapping").asText(),
                "SEMANTIC_CONTRACT", rules.path("semantic_contract_version").asText());
        Map<String, String> names = Map.of("QUESTION_CATALOG", "questionCatalog", "OPTION_CATALOG", "optionCatalog",
                "OPTION_VARIANT", "optionVariant", "MAJOR_MAPPING", "majorMapping", "SEMANTIC_CONTRACT", "semanticContract");
        for (var entry : requested.entrySet()) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM exploration_catalog WHERE catalog_type=? AND version=?",
                    entry.getKey(), entry.getValue());
            if (rows.size() != 1) throw ExplorationApiException.integrity();
            Map<String, Object> root = rows.get(0);
            reviewed(root);
            catalogs.put(entry.getKey(), root);
            versions.put(names.get(entry.getKey()), entry.getValue());
            hashes.put(names.get(entry.getKey()), root.get("content_hash").toString());
        }
        long questionId = id(catalogs.get("QUESTION_CATALOG")), optionId = id(catalogs.get("OPTION_CATALOG")),
                variantId = id(catalogs.get("OPTION_VARIANT")), mappingId = id(catalogs.get("MAJOR_MAPPING")),
                semanticId = id(catalogs.get("SEMANTIC_CONTRACT"));
        ArrayNode questions = rows("exploration_question", questionId, "question_id", true);
        ArrayNode options = rows("exploration_option", optionId, "question_id, comparison_set_key, variant_version_key, option_order, option_code", false);
        ArrayNode majors = rows("exploration_major_mapping", mappingId, "major_code", true);
        if (questions.size() != 187 || options.size() != 1360 || majors.size() != 94) throw ExplorationApiException.integrity();
        JsonNode variant = json.read(catalogs.get("OPTION_VARIANT").get("metadata_json").toString());
        variant.path("variants").forEach(this::reviewedJson);
        if (variant.path("variants").size() != 32) throw ExplorationApiException.integrity();
        payload.set("questions", questions);
        payload.set("options", options);
        payload.set("variant", variant);
        ObjectNode mapping = payload.putObject("mapping");
        mapping.put("schema_version", catalogs.get("MAJOR_MAPPING").get("source_schema_version").toString());
        mapping.put("major_mapping_version", requested.get("MAJOR_MAPPING"));
        mapping.set("majors", majors);
        payload.set("semantic", json.read(catalogs.get("SEMANTIC_CONTRACT").get("metadata_json").toString()));
        payload.set("displayContent", displayCatalog.snapshot());
        payload.set("comparisonQuestionDisplay", comparisonQuestionDisplay.snapshot());
        payload.set("cautionTags", json.tree(jdbc.queryForList("SELECT m.major_code AS majorCode,t.tag_code AS tagCode,"
                + "t.proposal_status AS proposalStatus,t.content_status AS contentStatus,"
                + "t.implementation_review_status AS implementationReviewStatus,t.domain_review_status AS domainReviewStatus,t.evidence "
                + "FROM exploration_caution_tag t JOIN exploration_major_mapping m ON m.id=t.mapping_id "
                + "WHERE m.catalog_id=? ORDER BY m.major_code,t.tag_code", mappingId)));
        BranchCatalog branch = branch(payload);
        return new Frozen(new VersionBundle(questionId, optionId, variantId, mappingId, id(rule), semanticId), json.hex(payload), payload, branch);
    }

    public void pin(long sessionId, Frozen frozen) {
        // Atomic insert-or-reuse across users; no existing snapshot is ever overwritten.
        jdbc.update("INSERT INTO exploration_runtime_snapshot(content_hash,payload_json) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE content_hash=content_hash", frozen.hash(), json.write(frozen.payload()));
        jdbc.update("INSERT INTO exploration_session_pin(session_id,snapshot_hash) VALUES (?,?)", sessionId, frozen.hash());
    }

    public Frozen load(long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT p.snapshot_hash,s.payload_json FROM exploration_session_pin p "
                + "JOIN exploration_runtime_snapshot s ON s.content_hash=p.snapshot_hash WHERE p.session_id=?", sessionId);
        if (rows.size() != 1) throw ExplorationApiException.integrity();
        JsonNode payload = json.read(rows.get(0).get("payload_json").toString());
        String hash = rows.get(0).get("snapshot_hash").toString();
        if (!hash.equals(json.hex(payload))) throw ExplorationApiException.integrity();
        return new Frozen(null, hash, payload, branch(payload));
    }

    public ObjectNode questionSnapshot(Frozen frozen, BranchCatalog.Key key) {
        frozen.branch().validate(key);
        ObjectNode out = json.object();
        JsonNode question = null;
        for (JsonNode row : frozen.payload().path("questions")) if (row.path("question_id").asText().equals(key.questionId())) question = row;
        if (question == null) throw ExplorationApiException.integrity();
        ObjectNode displayedQuestion = question.deepCopy();
        String original = question.path("question_text").asText();
        if (!key.comparisonSet().isEmpty()) {
            displayedQuestion.put("question_text", ComparisonQuestionDisplayCatalog.text(
                    frozen.payload().path("comparisonQuestionDisplay"), key.comparisonSet(), original));
        }
        out.set("question", displayedQuestion);
        ArrayNode options = out.putArray("options");
        for (JsonNode row : frozen.payload().path("options")) {
            if (row.path("question_id").asText().equals(key.questionId())
                    && row.path("comparison_set").asText().equals(key.comparisonSet())) options.add(row.deepCopy());
        }
        if (options.isEmpty()) throw ExplorationApiException.integrity();
        out.put("bundleHash", frozen.hash());
        out.set("versions", frozen.payload().path("versions").deepCopy());
        out.set("sourceHashes", frozen.payload().path("sourceHashes").deepCopy());
        out.put("optionVariantVersion", key.variantVersion());
        out.set("semantics", json.tree(frozen.branch().semantics(key)));
        return out;
    }

    private BranchCatalog branch(JsonNode payload) {
        try {
            return new BranchCatalog(payload.path("rules"), payload.path("mapping"), payload.path("semantic"),
                    csv(payload.path("questions")), csv(payload.path("options")),
                    csv(payload.path("variant").path("variants")), csv(payload.path("variant").path("same_direction_skip_pairs")));
        } catch (IllegalArgumentException e) { throw ExplorationApiException.integrity(); }
    }

    private ArrayNode rows(String table, long catalogId, String order, boolean statuses) {
        ArrayNode result = json.array();
        String column = table.equals("exploration_major_mapping") ? "mapping_json" : "metadata_json";
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + table + " WHERE catalog_id=? ORDER BY " + order, catalogId)) {
            if (statuses) reviewed(row);
            JsonNode metadata = json.read(row.get(column).toString());
            if (statuses) reviewedJson(metadata);
            result.add(metadata);
        }
        return result;
    }

    private void reviewed(Map<String, Object> row) {
        if (!Set.of("REVIEWED", "ACTIVE").contains(row.get("content_status"))
                || !"APPROVED_FOR_IMPLEMENTATION".equals(row.get("implementation_review_status"))
                || "REJECTED".equals(row.get("domain_review_status"))) throw new ExplorationApiException(503, "NO_ACTIVE_CATALOG", "개발 검토 요건을 충족하지 않은 자료입니다.");
    }

    private void reviewedJson(JsonNode row) {
        reviewed(Map.of("content_status", row.path("content_status").asText(),
                "implementation_review_status", row.path("implementation_review_status").asText(),
                "domain_review_status", row.path("domain_review_status").asText()));
    }

    private static long id(Map<String, Object> row) { return ((Number) row.get("id")).longValue(); }

    private static String csv(JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) throw ExplorationApiException.integrity();
        TreeSet<String> fields = new TreeSet<>();
        rows.get(0).fieldNames().forEachRemaining(fields::add);
        StringBuilder csv = new StringBuilder(String.join(",", fields)).append('\n');
        for (JsonNode row : rows) {
            StringJoiner line = new StringJoiner(",");
            for (String field : fields) line.add("\"" + row.path(field).asText().replace("\"", "\"\"") + "\"");
            csv.append(line).append('\n');
        }
        return csv.toString();
    }
}
