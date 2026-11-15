package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.branching.CandidateEngine.FixedCandidates;
import com.jinroon.jobe.domain.exploration.branching.CandidateEngine.GroupScore;
import com.jinroon.jobe.domain.exploration.branching.QuestionBranchEngine;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.entity.ExplorationSession;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stored server decisions, not client-supplied candidates or mutable live catalog guesses. */
@Component
@RequiredArgsConstructor
public class ExplorationStageStore {
    private final JdbcTemplate jdbc;
    private final ExplorationJson json;

    public record Checkpoint(long id, String hash, JsonNode payload, FixedCandidates candidates,
                             QuestionBranchEngine.Plan plan) {}

    public Checkpoint enter(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen,
                            FixedCandidates fixed, List<BranchCatalog.Answer> responses, List<BranchCatalog.Key> history) {
        try {
            QuestionBranchEngine engine = new QuestionBranchEngine(frozen.branch());
            ObjectNode payload = json.object();
            payload.put("stage", session.getStage());
            payload.put("bundleHash", frozen.hash());
            payload.put("enteredRevision", session.getRevision());
            payload.set("candidates", json.tree(fixed));
            payload.set("inputAnswers", json.tree(responses));
            payload.set("inputHistory", json.tree(history));
            payload.set("presentation", json.tree(Map.of("unexpected", List.of(), "unfamiliar", List.of(), "source", "NOT_COLLECTED")));
            QuestionBranchEngine.Plan plan;
            if ("STAGE_2".equals(session.getStage())) {
                plan = engine.stageTwo(fixed, responses, history, QuestionBranchEngine.Presentation.none());
            } else if ("STAGE_3".equals(session.getStage())) {
                var tags = developmentTags(session, frozen);
                payload.set("eligibleTags", tags);
                Map<String, Set<String>> eligible = new TreeMap<>();
                for (JsonNode tag : tags) eligible.computeIfAbsent(tag.path("majorCode").asText(), k -> new TreeSet<>()).add(tag.path("tagCode").asText());
                plan = engine.stageThree(fixed, responses, history, eligible);
            } else throw ExplorationApiException.integrity();
            payload.set("plan", json.tree(plan));
            String hash = json.hex(payload);
            jdbc.update("INSERT INTO exploration_stage_checkpoint(session_id,stage,entered_revision,payload_hash,payload_json) VALUES (?,?,?,?,?)",
                    session.getId(), session.getStage(), session.getRevision(), hash, json.write(payload));
            return load(session.getId(), session.getStage(), frozen);
        } catch (IllegalArgumentException e) { throw ExplorationApiException.integrity(); }
    }

    public Checkpoint load(long sessionId, String stage, ExplorationRuntimeCatalog.Frozen frozen) {
        var rows = jdbc.queryForList("SELECT * FROM exploration_stage_checkpoint WHERE session_id=? AND stage=? AND valid=true", sessionId, stage);
        if (rows.size() != 1) throw ExplorationApiException.integrity();
        var row = rows.get(0);
        JsonNode payload = json.read(row.get("payload_json").toString());
        String hash = row.get("payload_hash").toString();
        if (!hash.equals(json.hex(payload)) || !frozen.hash().equals(payload.path("bundleHash").asText())
                || !stage.equals(payload.path("stage").asText())
                || ((Number)row.get("entered_revision")).longValue() != payload.path("enteredRevision").asLong()) throw ExplorationApiException.integrity();
        try {
            JsonNode c = payload.path("candidates");
            List<GroupScore> groups = new ArrayList<>();
            c.path("rankedGroups").forEach(g -> groups.add(new GroupScore(g.path("code").asText(), g.path("score").asDouble())));
            FixedCandidates fixed = new FixedCandidates(groups, strings(c.path("groupCodes")), strings(c.path("majorCodes")));
            new QuestionBranchEngine(frozen.branch()).candidates().validate(fixed);
            JsonNode p = payload.path("plan");
            var plan = new QuestionBranchEngine.Plan(keys(p.path("active")), keys(p.path("toIssue")), keys(p.path("omitted")), p.path("budgetLimited").asBoolean());
            for (var key : plan.active()) {
                frozen.branch().validate(key);
                if (!stage.equals(frozen.branch().question(key.questionId()).stage())) throw ExplorationApiException.integrity();
            }
            return new Checkpoint(((Number)row.get("id")).longValue(), hash, payload, fixed, plan);
        } catch (IllegalArgumentException e) { throw ExplorationApiException.integrity(); }
    }

    public void invalidateAfter(long sessionId, String stage) {
        jdbc.update("UPDATE exploration_stage_checkpoint SET valid=false WHERE session_id=? AND valid=true AND stage>?", sessionId, stage);
    }

    private com.fasterxml.jackson.databind.node.ArrayNode developmentTags(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen) {
        // Runtime policy already restricts these sessions to isolated DEV. No PUBLIC/PILOT approval is inferred.
        if (!session.getRunMode().name().equals("DEV")) throw ExplorationApiException.integrity();
        Map<String, Set<String>> approved = new TreeMap<>();
        for (JsonNode major : frozen.payload().path("mapping").path("majors"))
            approved.put(major.path("major_code").asText(), new HashSet<>(strings(major.path("approved_caution_tags"))));
        var result = json.array();
        var rows = jdbc.queryForList("SELECT t.*,m.major_code FROM exploration_caution_tag t JOIN exploration_major_mapping m ON m.id=t.mapping_id "
                + "WHERE m.catalog_id=? ORDER BY m.major_code,t.tag_code", session.getMajorMappingCatalogId());
        for (var row : rows) {
            String code = row.get("major_code").toString(), tag = row.get("tag_code").toString();
            if (!"PROPOSED".equals(row.get("proposal_status"))
                    || !Set.of("REVIEWED", "ACTIVE").contains(row.get("content_status"))
                    || !"APPROVED_FOR_IMPLEMENTATION".equals(row.get("implementation_review_status"))
                    || "REJECTED".equals(row.get("domain_review_status"))
                    || row.get("evidence") == null || row.get("evidence").toString().isBlank()
                    || !approved.getOrDefault(code, Set.of()).contains(tag)) continue;
            ObjectNode saved = result.addObject();
            saved.put("majorCode", code); saved.put("tagCode", tag);
            saved.put("sourceId", ((Number)row.get("id")).longValue());
            saved.put("evidence", row.get("evidence").toString());
            saved.put("contentStatus", row.get("content_status").toString());
            saved.put("implementationReviewStatus", row.get("implementation_review_status").toString());
            saved.put("domainReviewStatus", row.get("domain_review_status").toString());
        }
        return result;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw ExplorationApiException.integrity();
        List<String> values = new ArrayList<>(); node.forEach(n -> values.add(n.asText())); return List.copyOf(values);
    }
    private static List<BranchCatalog.Key> keys(JsonNode node) {
        if (!node.isArray()) throw ExplorationApiException.integrity();
        List<BranchCatalog.Key> keys = new ArrayList<>();
        node.forEach(k -> keys.add(new BranchCatalog.Key(k.path("questionId").asText(), k.path("questionVersion").asText(),
                k.path("comparisonSet").asText(), k.path("variantVersion").asText())));
        return List.copyOf(keys);
    }
}
