package com.jinroon.jobe.domain.exploration.display;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Pure presentation over sealed evidence. Never reads a live catalog, reranks, writes, or calls AI. */
@Component
@RequiredArgsConstructor
public class ExplorationResultDisplay {
    private final ExplorationJson json;
    private final ResultDisplayCatalog catalog;

    public ObjectNode freeze(JsonNode result, JsonNode bundle) {
        JsonNode wording = bundle.path("displayContent");
        if (wording.isMissingNode()) wording = catalog.snapshot(); // Sessions pinned before display support.
        ObjectNode display = build(result, wording);
        display.put("source", "COMPLETION_SNAPSHOT");
        display.put("cautionAvailability", bundle.has("cautionTags") ? "EVALUATED" : "UNAVAILABLE");
        display.put("cautionUnavailableReason", bundle.has("cautionTags") ? null : "APPROVAL_SNAPSHOT_NOT_AVAILABLE");
        appendCautions(display, result, bundle, wording);
        return display;
    }

    public JsonNode project(JsonNode result) {
        if (result.has("display")) return result.path("display").deepCopy();
        ObjectNode display = build(result, catalog.snapshot());
        display.put("source", "SEALED_RESULT_PROJECTION");
        display.put("cautionAvailability", "UNAVAILABLE");
        display.put("cautionUnavailableReason", "APPROVAL_SNAPSHOT_NOT_AVAILABLE");
        return display;
    }

    /** Read sealed used evidence only. No scoring, reranking, live wording fallback, or current catalog lookup. */
    public String candidateEvidenceLevel(JsonNode candidate) {
        return level(evidence(candidate, json.object()));
    }

    public ObjectNode candidateDescription(JsonNode candidate, JsonNode wording) {
        ObjectNode out = json.object();
        out.put("evidenceLevel", candidateEvidenceLevel(candidate));
        boolean available = wording.isObject() && wording.path("version").isTextual() && wording.path("metrics").isObject();
        for (String field : List.of("usedInterestAxes", "usedActivityAxes", "usedLearningSets"))
            for (JsonNode code : candidate.path(field)) available &= wording.path("metrics").path(code.asText()).isTextual();
        var reasons = out.putArray("reasons");
        if (available) {
            reason(reasons, "INTEREST", candidate.path("usedInterestAxes"), wording);
            reason(reasons, "ACTIVITY", candidate.path("usedActivityAxes"), wording);
            reason(reasons, "LEARNING", candidate.path("usedLearningSets"), wording);
            out.put("explanation", reasons.get(0).path("text").asText() + " 능력이나 합격 가능성을 판정한 결과가 아닙니다.");
            out.put("reasonAvailability", "AVAILABLE"); out.putNull("reasonUnavailableCode");
        } else {
            out.putNull("explanation"); out.put("reasonAvailability", "UNAVAILABLE");
            out.put("reasonUnavailableCode", "HISTORICAL_DISPLAY_CONTENT_UNAVAILABLE");
        }
        return out;
    }

    private ObjectNode build(JsonNode result, JsonNode wording) {
        ObjectNode out = json.object();
        out.put("version", wording.path("version").asText());
        out.put("contentHash", json.hex(wording));
        out.put("scope", "DEV"); out.put("domainReviewStatus", "PENDING");
        out.put("evidencePolicy", "USED_INTEREST_AXES_MIN_WITH_INCONSISTENCY");
        var majors = out.putArray("majors");
        Map<String, JsonNode> candidates = new LinkedHashMap<>();
        result.path("rankedCandidates").forEach(m -> candidates.put(m.path("majorCode").asText(), m));
        JsonNode presentation = result.path("presentation");
        boolean collected = "USER_REPORTED".equals(presentation.path("source").asText());
        Set<String> unfamiliar = new HashSet<>(), unexpected = new HashSet<>();
        presentation.path("unfamiliar").forEach(n -> unfamiliar.add(n.asText()));
        presentation.path("unexpected").forEach(n -> unexpected.add(n.asText()));
        String range = presentation.path("unfamiliarRange").asText();
        int unfamiliarLimit = switch (range) { case "OPEN_MAIN_ONLY" -> 0; case "OPEN_ONE_EXTRA" -> 1; default -> Integer.MAX_VALUE; };
        int unfoldedUnfamiliar = 0;
        for (JsonNode source : result.path("majorSummaries")) {
            if (majors.size() == 7) break;
            ObjectNode row = source.deepCopy();
            row.put("name", source.path("majorName").asText()); row.putNull("majorId");
            row.put("collapsed", false); row.put("collapseReasonCode", "PRESENTATION_NOT_COLLECTED");
            if (collected) {
                boolean collapsed = unfamiliar.contains(source.path("majorCode").asText()) && unfoldedUnfamiliar++ >= unfamiliarLimit;
                row.put("collapsed", collapsed);
                row.put("collapseReasonCode", collapsed ? "OPENNESS_UNFAMILIAR_RANGE" : "USER_REACTION_COLLECTED");
                row.put("unexpected", unexpected.contains(source.path("majorCode").asText()));
                row.put("unfamiliar", unfamiliar.contains(source.path("majorCode").asText()));
                if (unexpected.contains(source.path("majorCode").asText()) && presentation.has("unexpectedAction"))
                    row.set("unexpectedAction", presentation.path("unexpectedAction").deepCopy());
            }
            JsonNode candidate = candidates.getOrDefault(source.path("majorCode").asText(), json.object());
            var axes = evidence(candidate, source);
            row.put("evidenceLevel", level(axes));
            row.set("reasonCodes", reasonCodes(axes));
            var reasons = row.putArray("reasons");
            reason(reasons, "INTEREST", source.path("usedInterestAxes"), wording);
            reason(reasons, "ACTIVITY", source.path("usedActivityAxes"), wording);
            reason(reasons, "LEARNING", source.path("usedLearningSets"), wording);
            String text = reasons.get(0).path("text").asText();
            row.put("explanation", text + " 능력이나 합격 가능성을 판정한 결과가 아닙니다.");
            majors.add(row);
        }
        var groups = out.putArray("groups");
        for (JsonNode source : result.path("groupSummaries")) {
            if (groups.size() == 3) break;
            ObjectNode row = source.deepCopy();
            JsonNode label = wording.path("groups").path(source.path("groupCode").asText());
            row.set("name", label.path("name")); row.set("summary", label.path("summary"));
            row.put("labelAvailability", label.isMissingNode() ? "UNAVAILABLE" : "AVAILABLE");
            ArrayNode nested = row.putArray("majors"); var axes = json.array();
            for (JsonNode major : majors) if (major.path("groupCode").equals(source.path("groupCode"))) {
                nested.add(major.deepCopy());
                axes.addAll(evidence(candidates.getOrDefault(major.path("majorCode").asText(), json.object()), major));
            }
            row.put("evidenceLevel", level(axes)); row.set("reasonCodes", reasonCodes(axes)); groups.add(row);
        }
        var recheck = out.putArray("recheckAxes");
        JsonNode interest = result.path("axes").path("interest");
        var codes = new TreeSet<String>(); interest.fieldNames().forEachRemaining(codes::add);
        for (String code : codes) {
            JsonNode axis = interest.path(code);
            if (axis.path("validCount").asInt() >= 2 && !axis.path("inconsistent").asBoolean()) continue;
            var row = recheck.addObject(); row.put("metricCode", code); row.set("name", wording.path("metrics").path(code));
            row.put("reasonCode", axis.path("inconsistent").asBoolean() ? "INTEREST_RESPONSES_INCONSISTENT" : "INTEREST_RESPONSES_INSUFFICIENT");
        }
        out.putArray("cautions");
        return out;
    }

    private ArrayNode evidence(JsonNode candidate, JsonNode summary) {
        var rows = json.array();
        JsonNode interest = candidate.path("evidence").path("interest");
        for (JsonNode code : summary.path("usedInterestAxes")) {
            JsonNode axis = interest.path(code.asText());
            if (!axis.isMissingNode()) rows.add(axis);
            else for (JsonNode fallback : summary.path("interestEvidence")) if (code.asText().equals(fallback.path("metricCode").asText())) rows.add(fallback);
        }
        return rows;
    }

    private String level(ArrayNode axes) {
        if (axes.isEmpty()) return "NO_EVIDENCE";
        if (java.util.stream.StreamSupport.stream(axes.spliterator(), false).anyMatch(a -> a.path("validCount").asInt() == 0)) return "NO_EVIDENCE";
        for (JsonNode axis : axes) if (axis.path("validCount").asInt() < 2 || axis.path("inconsistent").asBoolean()) return "LOW_EVIDENCE";
        return "CONFIRMED";
    }

    private ArrayNode reasonCodes(ArrayNode axes) {
        var codes = json.array(); var set = new TreeSet<String>();
        if (axes.isEmpty()) set.add("NO_USED_INTEREST_EVIDENCE");
        for (JsonNode axis : axes) {
            if (axis.path("validCount").asInt() < 2) set.add("USED_INTEREST_AXIS_INSUFFICIENT");
            if (axis.path("inconsistent").asBoolean()) set.add("USED_INTEREST_AXIS_INCONSISTENT");
        }
        set.forEach(codes::add); return codes;
    }

    private void reason(ArrayNode reasons, String kind, JsonNode codes, JsonNode wording) {
        var row = reasons.addObject(); row.put("kind", kind);
        List<String> labels = new ArrayList<>();
        for (JsonNode code : codes) if (wording.path("metrics").path(code.asText()).isTextual()) labels.add(wording.path("metrics").path(code.asText()).asText());
        String subject = switch (kind) { case "INTEREST" -> "관심"; case "ACTIVITY" -> "활동"; default -> "학습"; };
        boolean available = !labels.isEmpty();
        row.put("reasonCode", kind + (available ? "_RESPONSES_USED" : "_EVIDENCE_NOT_USED"));
        row.put("text", available ? String.join("·", labels) + "에 관한 " + subject + " 응답을 이 전공의 탐색 단서로 함께 참고했습니다."
                : subject + " 응답이 충분하지 않아 해당 근거를 탐색 단서로 사용하지 않았습니다. 추가로 확인해볼 수 있습니다.");
    }

    private void appendCautions(ObjectNode out, JsonNode result, JsonNode bundle, JsonNode wording) {
        Map<String, JsonNode> mappings = new HashMap<>();
        bundle.path("mapping").path("majors").forEach(m -> mappings.put(m.path("major_code").asText(), m));
        ArrayNode cautions = (ArrayNode)out.path("cautions");
        for (JsonNode major : result.path("majorSummaries")) {
            JsonNode mapping = mappings.get(major.path("majorCode").asText());
            if (mapping == null) continue;
            var approved = new HashSet<String>(); mapping.path("approved_caution_tags").forEach(t -> approved.add(t.asText()));
            for (JsonNode tag : bundle.path("cautionTags")) {
                String code = tag.path("tagCode").asText();
                if (cautions.size() >= 2) return;
                if (!major.path("majorCode").asText().equals(tag.path("majorCode").asText()) || !approved.contains(code)
                        || !"PROPOSED".equals(tag.path("proposalStatus").asText())
                        || !Set.of("REVIEWED", "ACTIVE").contains(tag.path("contentStatus").asText())
                        || !"APPROVED_FOR_IMPLEMENTATION".equals(tag.path("implementationReviewStatus").asText())
                        || "REJECTED".equals(tag.path("domainReviewStatus").asText()) || tag.path("evidence").asText().isBlank()) continue;
                JsonNode text = wording.path("cautions").path(code); if (text.isMissingNode()) continue;
                var row = cautions.addObject(); row.put("majorCode", major.path("majorCode").asText()); row.put("tagCode", code);
                row.set("text", text.path("text")); row.set("checkAction", text.path("checkAction"));
                row.put("usage", "DEV_EXPLORATION_CHECK_ONLY"); row.put("scope", "DEV");
            }
        }
    }
}
