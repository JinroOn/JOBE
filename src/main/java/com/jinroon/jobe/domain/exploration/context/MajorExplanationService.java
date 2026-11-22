package com.jinroon.jobe.domain.exploration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiContract;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiInputAdapter;
import com.jinroon.jobe.domain.exploration.ai.ExplorationModelAvailability;
import com.jinroon.jobe.domain.exploration.ai.OwnedGenerationStore;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MajorExplanationService {
    private static final String PURPOSE = "EXPLANATION";
    // 최신 순서. 프롬프트 버전이 바뀌기 전에 저장된 설명을 찾는다.
    private static final List<String> PREVIOUS_SUFFIXES = List.of("-v10", "-v9", "-v8", "-v7", "-v6", "-v5");
    // Previous releases stored AI model output HTTP 422 errors as INVALID_REQUEST.
    private static final Set<String> RETRYABLE_FAILURES = Set.of("OUTPUT_INVALID", "INVALID_REQUEST");
    private final ResultContextService contexts;
    private final OwnedGenerationStore jobs;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationModelAvailability availability;
    private final ExplorationJson json;

    private record Generation(String mutation, JsonNode task) {}

    @Transactional
    public ObjectNode request(long user, long resultId, String majorCode) {
        if (!available()) return get(user, resultId, majorCode);
        var context = contexts.prepareExplanation(user, resultId, majorCode);
        var existing = view(user, majorCode, context);
        if (!"NOT_REQUESTED".equals(existing.path("status").asText())) return existing;
        String mutation = mutation(context);
        var request = adapter.build(context.snapshot(), mutation);
        jobs.reserveModel(user, context.contextId(), PURPOSE, mutation, request);
        return view(user, majorCode, context);
    }

    @Transactional
    public ObjectNode retry(long user, long resultId, String majorCode) {
        var context = contexts.findExplanation(user, resultId, majorCode);
        if (context == null) {
            throw new ExplorationApiException(409, "EXPLANATION_RETRY_UNAVAILABLE", "다시 시도할 설명이 없어요.");
        }
        var generation = originalGeneration(user, context);
        String originalMutation = generation.mutation();
        var original = generation.task();
        String retryMutation = originalMutation + "-retry";
        var retry = task(user, context, retryMutation);
        if (retry != null) return view(user, majorCode, context, retryMutation, retry, false);
        if (!canRetry(original)) return view(user, majorCode, context, originalMutation, original, false);
        jobs.reserveModel(user, context.contextId(), PURPOSE, retryMutation, adapter.build(context.snapshot(), retryMutation));
        return view(user, majorCode, context, retryMutation, task(user, context, retryMutation), false);
    }

    @Transactional
    public ObjectNode get(long user, long resultId, String majorCode) {
        var context = contexts.findExplanation(user, resultId, majorCode);
        if (context == null) return status(majorCode, available() ? "NOT_REQUESTED" : "UNAVAILABLE");
        return view(user, majorCode, context);
    }

    private ObjectNode view(long user, String majorCode, ResultContextService.LockedInput context) {
        var generation = originalGeneration(user, context);
        String originalMutation = generation.mutation();
        var original = generation.task();
        if (original == null) return status(majorCode, available() ? "NOT_REQUESTED" : "UNAVAILABLE");
        if ("FAILED".equals(original.path("status").asText())) {
            // 이전 버전에서 실패한 설명은 현재 버전으로 새로 요청할 수 있다.
            if (available() && !originalMutation.equals(mutation(context))
                    && !"EVIDENCE_UNAVAILABLE".equals(original.path("failureCode").asText())) {
                return status(majorCode, "NOT_REQUESTED");
            }
            String retryMutation = originalMutation + "-retry";
            var retry = task(user, context, retryMutation);
            if (retry != null) return view(user, majorCode, context, retryMutation, retry, false);
        }
        return view(user, majorCode, context, originalMutation, original, canRetry(original));
    }

    private Generation originalGeneration(long user, ResultContextService.LockedInput context) {
        String current = mutation(context);
        var currentTask = task(user, context, current);
        if (currentTask != null) return new Generation(current, currentTask);
        String legacy = legacyMutation(context);
        for (String suffix : PREVIOUS_SUFFIXES) {
            var previousTask = task(user, context, legacy + suffix);
            if (previousTask != null) return new Generation(legacy + suffix, previousTask);
        }
        return new Generation(legacy, task(user, context, legacy));
    }

    private JsonNode task(long user, ResultContextService.LockedInput context, String mutation) {
        return jobs.status(user, context.contextId(), PURPOSE, mutation, adapter.build(context.snapshot(), mutation));
    }

    private boolean canRetry(JsonNode task) {
        return task != null && "FAILED".equals(task.path("status").asText())
                && RETRYABLE_FAILURES.contains(task.path("failureCode").asText()) && available();
    }

    private ObjectNode view(long user, String majorCode, ResultContextService.LockedInput context,
                            String mutation, JsonNode task, boolean canRetry) {
        if (task == null) throw ExplorationApiException.integrity();
        return switch (task.path("status").asText()) {
            case "QUEUED", "RUNNING" -> status(majorCode, available() ? "GENERATING" : "UNAVAILABLE");
            case "MODEL_STORED" -> completed(user, majorCode, context, mutation, adapter.build(context.snapshot(), mutation));
            case "FAILED" -> "EVIDENCE_UNAVAILABLE".equals(task.path("failureCode").asText())
                    ? status(majorCode, "INSUFFICIENT_EVIDENCE") : status(majorCode, "FAILED").put("retryAvailable", canRetry);
            case "CANCELLED" -> status(majorCode, "UNAVAILABLE");
            default -> throw ExplorationApiException.integrity();
        };
    }

    private ObjectNode completed(long user, String majorCode, ResultContextService.LockedInput context,
                                 String mutation, JsonNode request) {
        var stored = jobs.completedModel(user, context.contextId(), PURPOSE, mutation);
        if (stored == null) return status(majorCode, "UNAVAILABLE");
        if (available() && !ExplorationAiContract.EXPLANATION_PROMPT_VERSION.equals(stored.path("promptVersion").asText())) {
            return status(majorCode, "NOT_REQUESTED");
        }
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
        return legacyMutation(context) + "-v11";
    }

    private String legacyMutation(ResultContextService.LockedInput context) {
        return "major-explanation-" + context.contextId();
    }
}
