package com.jinroon.jobe.domain.exploration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiInputAdapter;
import com.jinroon.jobe.domain.exploration.ai.ExplorationModelAvailability;
import com.jinroon.jobe.domain.exploration.ai.OwnedGenerationStore;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MajorExplanationService {
    private static final String PURPOSE = "EXPLANATION";
    private final ResultContextService contexts;
    private final OwnedGenerationStore jobs;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationModelAvailability availability;
    private final ExplorationJson json;

    @Transactional
    public ObjectNode request(long user, long resultId, String majorCode) {
        if (!available()) return get(user, resultId, majorCode);
        var context = contexts.prepareExplanation(user, resultId, majorCode);
        String mutation = mutation(context);
        var request = adapter.build(context.snapshot(), mutation);
        jobs.reserveModel(user, context.contextId(), PURPOSE, mutation, request);
        return view(user, majorCode, context);
    }

    public ObjectNode get(long user, long resultId, String majorCode) {
        var context = contexts.findExplanation(user, resultId, majorCode);
        if (context == null) return status(majorCode, available() ? "NOT_REQUESTED" : "UNAVAILABLE");
        return view(user, majorCode, context);
    }

    private ObjectNode view(long user, String majorCode, ResultContextService.LockedInput context) {
        String mutation = mutation(context);
        var request = adapter.build(context.snapshot(), mutation);
        var task = jobs.status(user, context.contextId(), PURPOSE, mutation, request);
        if (task == null) throw ExplorationApiException.integrity();
        return switch (task.path("status").asText()) {
            case "QUEUED", "RUNNING" -> status(majorCode, available() ? "GENERATING" : "UNAVAILABLE");
            case "MODEL_STORED" -> completed(user, majorCode, context, mutation, request);
            case "FAILED" -> status(majorCode, "EVIDENCE_UNAVAILABLE".equals(task.path("failureCode").asText())
                    ? "INSUFFICIENT_EVIDENCE" : "FAILED");
            case "CANCELLED" -> status(majorCode, "UNAVAILABLE");
            default -> throw ExplorationApiException.integrity();
        };
    }

    private ObjectNode completed(long user, String majorCode, ResultContextService.LockedInput context,
                                 String mutation, JsonNode request) {
        var stored = jobs.completedModel(user, context.contextId(), PURPOSE, mutation);
        if (stored == null) return status(majorCode, "UNAVAILABLE");
        var response = stored.path("response");
        if (!"SUCCEEDED".equals(response.path("status").asText())) return status(majorCode, "INSUFFICIENT_EVIDENCE");
        var result = status(majorCode, "READY");
        var connections = result.putArray("connections");
        for (JsonNode item : response.path("result").path("connections")) {
            String questionId = item.path("questionId").asText();
            var signal = findSignal(request, questionId);
            connections.addObject()
                    .put("kind", signal.path("kind").asText())
                    .put("question", signal.path("questionText").asText())
                    .put("answer", signal.path("answerText").asText())
                    .put("connection", item.path("connection").asText());
        }
        return result;
    }

    private JsonNode findSignal(JsonNode request, String questionId) {
        for (JsonNode signal : request.path("input").path("signals")) {
            if (questionId.equals(signal.path("questionId").asText())) return signal;
        }
        throw ExplorationApiException.integrity();
    }

    private boolean available() {
        return availability.explanationAvailableFor(ExplorationRunMode.DEV);
    }

    private ObjectNode status(String code, String state) {
        return json.object().put("majorCode", code).put("status", state);
    }

    private String mutation(ResultContextService.LockedInput context) {
        return "major-explanation-" + context.contextId();
    }
}
