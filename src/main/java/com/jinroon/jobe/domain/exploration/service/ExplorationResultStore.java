package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.entity.ExplorationResult;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationResultStatus;
import com.jinroon.jobe.domain.exploration.repository.ExplorationResultRepository;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExplorationResultStore {
    private final ExplorationResultRepository results;
    private final JdbcTemplate jdbc;
    private final ExplorationJson json;
    private final com.jinroon.jobe.domain.exploration.sharing.ExplorationSharePolicy sharing;
    private final com.jinroon.jobe.domain.exploration.repository.ExplorationSessionRepository sessions;

    public ExplorationResult save(long sessionId, ObjectNode payload) {
        var result = results.saveAndFlush(ExplorationResult.of(sessionId, ExplorationResultAssembler.CONTRACT,
                ExplorationResultStatus.valueOf(payload.path("status").asText()), payload.path("completionReason").asText(),
                HexFormat.of().parseHex(payload.path("inputHash").asText()), json.write(payload)));
        jdbc.update("INSERT INTO exploration_result_seal(result_id,content_hash) VALUES (?,?)", result.getId(), json.hex(payload));
        return result;
    }

    public JsonNode verifiedSnapshot(ExplorationResult result) {
        JsonNode payload = json.read(result.getResultJson());
        var seal = jdbc.queryForList("SELECT content_hash FROM exploration_result_seal WHERE result_id=?", String.class, result.getId());
        if (seal.size() != 1 || !seal.get(0).equals(json.hex(payload))
                || !ExplorationResultAssembler.CONTRACT.equals(result.getResultContractVersion())
                || !result.getResultContractVersion().equals(payload.path("resultContractVersion").asText())
                || !result.getSessionId().equals(payload.path("sessionId").asLong())
                || !result.getResultStatus().name().equals(payload.path("status").asText())
                || !result.getCompletionReason().equals(payload.path("completionReason").asText())
                || !HexFormat.of().formatHex(result.getInputHash()).equals(payload.path("inputHash").asText())
                || !payload.path("inputHash").asText().equals(json.hex(payload.path("input")))) throw ExplorationApiException.integrity();
        return payload;
    }

    public JsonNode response(ExplorationResult result) {
        JsonNode payload = verifiedSnapshot(result);
        ObjectNode dto = json.object();
        dto.put("resultId", result.getId());
        for (String field : new String[]{"resultContractVersion", "sessionId", "sessionStatus", "completedAt", "status", "completionReason", "reasonCodes", "versions", "evidenceLevel", "cautions", "presentation", "runMode", "testOnly", "flags"})
            dto.set(field, payload.path(field).deepCopy());
        dto.put("resultVersion", payload.path("resultContractVersion").asText());
        dto.set("groups", payload.path("groupSummaries").deepCopy());
        dto.set("majors", payload.path("majorSummaries").deepCopy());
        // Current availability is separate from the immutable diagnosis payload.
        dto.set("nextActivities", json.array());
        dto.put("activityAvailability", "UNAVAILABLE");
        dto.put("activityMessage", "승인된 탐색 활동 안내를 준비 중입니다.");
        dto.put("activitiesEndpoint", "/api/exploration/v1/results/"+result.getId()+"/activities");
        ObjectNode capabilities = dto.putObject("capabilities");
        dto.put("comparisonEndpoint", "/api/exploration/v1/results/"+result.getId()+"/comparison");
        capabilities.put("comparisonAvailable",payload.path("rankedCandidates").size()>=2);
        capabilities.put("supportAvailable", true); capabilities.put("planAvailable", false);
        var session=sessions.findById(result.getSessionId()).orElseThrow(ExplorationApiException::integrity);
        capabilities.put("sharingAvailable",sharing.available(session.getUserId(),session.getRunMode()));
        dto.put("sharingEndpoint","/api/exploration/v1/results/"+result.getId()+"/share");
        dto.put("contextsEndpoint","/api/exploration/v1/results/"+result.getId()+"/contexts");
        return dto;
    }
}
