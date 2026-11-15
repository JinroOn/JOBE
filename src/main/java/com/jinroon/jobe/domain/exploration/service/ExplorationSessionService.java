package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.branching.QuestionBranchEngine;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.entity.*;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.user.service.UserWriteGuard;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExplorationSessionService {
    private final UserWriteGuard users;
    private final ExplorationSessionRepository sessions;
    private final ExplorationIssuedQuestionRepository issued;
    private final ExplorationAnswerRevisionRepository answers;
    private final ExplorationIdempotencyRepository idempotency;
    private final ExplorationRuntimePolicy policy;
    private final ExplorationRuntimeCatalog catalogs;
    private final ExplorationJson json;
    private final ExplorationStageStore stages;
    private final ExplorationResultRepository results;
    private final ExplorationResultAssembler resultAssembler;
    private final ExplorationResultStore resultStore;

    public record Reply(int status, JsonNode body) {}
    private record State(List<ExplorationIssuedQuestion> issued, Map<Long, String> answers, Map<Long, Integer> answerRevisions,
                         QuestionBranchEngine.StageOne one, QuestionBranchEngine.Plan plan) {}

    private record BranchChanges(List<Long> excludedIssuedIds, List<Long> restoredIssuedIds) {}

    @Transactional
    public Reply start(Long userId, String key, String diagnosisType) {
        validateKey(key);
        users.requireActive(userId); // D07: user first; same order as account deletion.
        var mode = policy.authorize(userId);
        byte[] hash = json.hash(Map.of("diagnosisType", diagnosisType));
        ExplorationIdempotency request = reservation(userId, "START", 0, key, hash);
        if (request.getResponseStatus() != null) return replay(request);
        sessions.findFirstByUserIdAndStatus(userId, ExplorationSessionStatus.in_progress).ifPresent(existing -> {
            throw new ExplorationApiException(409, "EXPLORATION_IN_PROGRESS", "진행 중인 탐색 회차가 있습니다.", existing.getId());
        });
        var frozen = catalogs.selectForDevelopment();
        ExplorationSession session = sessions.saveAndFlush(ExplorationSession.start(userId, mode, null, "STAGE_1", frozen.ids(), LocalDateTime.now()));
        catalogs.pin(session.getId(), frozen);
        return success(request, 201, sessionDto(session, frozen));
    }

    @Transactional
    public Reply next(Long userId, long sessionId, String key, long expectedRevision) {
        validateKey(key);
        users.requireActive(userId);
        ExplorationSession session = ownedLocked(userId, sessionId);
        policy.authorizeExisting(userId, session.getRunMode());
        ExplorationIdempotency request = reservation(userId, "NEXT", sessionId, key, json.hash(Map.of("expectedRevision", expectedRevision)));
        if (request.getResponseStatus() != null) return replay(request); // replay before revision/status validation
        writable(session, expectedRevision);
        var frozen = catalogs.load(sessionId);
        BranchChanges changes = reconcile(session, frozen);
        State state = state(session, frozen);
        boolean changed = !changes.excludedIssuedIds().isEmpty() || !changes.restoredIssuedIds().isEmpty();
        int ordinal = issued.findMaxOrdinal(sessionId);
        for (BranchCatalog.Key keyToIssue : state.plan().toIssue()) {
            issued.save(ExplorationIssuedQuestion.issue(sessionId, keyToIssue.questionId(), keyToIssue.questionVersion(),
                    nullable(keyToIssue.comparisonSet()), nullable(keyToIssue.variantVersion()), session.getStage(), ++ordinal,
                    true, json.write(catalogs.questionSnapshot(frozen, keyToIssue)), LocalDateTime.now()));
            changed = true;
        }
        issued.flush();
        if (changed) session.increaseRevision();
        State updated = state(session, frozen);
        ObjectNode response = json.object();
        response.put("revision", session.getRevision());
        ArrayNode questions = response.putArray("issuedQuestions");
        updated.issued().stream().filter(q -> q.getStage().equals(session.getStage()) && q.getEffective() && !updated.answers().containsKey(q.getId()))
                .forEach(q -> questions.add(questionDto(q, frozen)));
        response.set("progress", progress(session, frozen, updated));
        response.put("nextAction", nextAction(session, updated));
        response.put("adjusted", !changes.excludedIssuedIds().isEmpty() || !changes.restoredIssuedIds().isEmpty());
        return success(request, 200, response);
    }

    @Transactional
    public Reply answer(Long userId, long sessionId, long issuedId, String idempotencyKey,
                        long expectedRevision, String optionCode, String clientMutationId) {
        validateKey(idempotencyKey);
        validateKey(clientMutationId);
        users.requireActive(userId);
        ExplorationSession session = ownedLocked(userId, sessionId);
        ExplorationIssuedQuestion question = issued.findByIdAndSessionId(issuedId, sessionId).orElseThrow(this::notFound);
        policy.authorizeExisting(userId, session.getRunMode());
        byte[] hash = json.hash(Map.of("operation", "ANSWER", "sessionId", sessionId, "issuedId", issuedId,
                "expectedRevision", expectedRevision, "optionCode", optionCode, "clientMutationId", clientMutationId));
        ExplorationIdempotency request = reservation(userId, "ANSWER", issuedId, idempotencyKey, hash);
        if (request.getResponseStatus() != null) return replay(request);
        Optional<ExplorationAnswerRevision> mutation = answers.findByIssuedIdAndClientMutationId(issuedId, clientMutationId);
        if (mutation.isPresent()) {
            if (!Arrays.equals(mutation.get().getMutationRequestHash(), hash))
                throw new ExplorationApiException(409, "IDEMPOTENCY_CONFLICT", "같은 답변 변경 식별자의 요청 내용이 다릅니다.");
            return success(request, 200, json.read(mutation.get().getMutationResponseJson()));
        }
        writable(session, expectedRevision);
        var frozen = catalogs.load(sessionId);
        JsonNode snapshot = validatedSnapshot(question, frozen);
        if (!question.getEffective()) throw new ExplorationApiException(422, "QUESTION_NOT_EFFECTIVE", "현재 분기에서 답변할 수 없는 문항입니다.");
        if (stageNumber(question.getStage()) > stageNumber(session.getStage())) throw new ExplorationApiException(422, "QUESTION_NOT_EFFECTIVE", "현재 단계의 문항이 아닙니다.");
        boolean allowed = false;
        for (JsonNode option : snapshot.path("options")) if (option.path("option_code").asText().equals(optionCode)) allowed = true;
        if (!allowed) throw new ExplorationApiException(422, "OPTION_NOT_ISSUED", "발급된 선택지가 아닙니다.");
        var current = answers.findByIssuedIdAndCurrentTrue(issuedId);
        var latest = answers.findFirstByIssuedIdOrderByAnswerRevisionDesc(issuedId);
        if (!current.map(ExplorationAnswerRevision::getId).equals(latest.map(ExplorationAnswerRevision::getId)))
            throw ExplorationApiException.integrity();
        int answerRevision = latest.map(a -> Math.addExact(a.getAnswerRevision(), 1)).orElse(1);
        current.ifPresent(ExplorationAnswerRevision::release);
        answers.flush(); // release current BEFORE the INSERT touches the generated unique key
        var saved = answers.saveAndFlush(ExplorationAnswerRevision.record(issuedId, answerRevision, optionCode,
                clientMutationId, hash, "{}", LocalDateTime.now()));
        String previousStage = session.getStage();
        boolean rewind = stageNumber(question.getStage()) < stageNumber(previousStage);
        List<Long> rewound = new ArrayList<>();
        if (rewind) {
            stages.invalidateAfter(sessionId, question.getStage());
            for (var later : issued.findBySessionIdOrderByOrdinalAsc(sessionId)) {
                if (stageNumber(later.getStage()) > stageNumber(question.getStage()) && later.getEffective()) {
                    later.exclude(); rewound.add(later.getId());
                }
            }
            session.moveToStage(question.getStage()); // revision increases exactly once for the whole answer transaction
        }
        BranchChanges changes = reconcile(session, frozen);
        if (!rewind) session.increaseRevision();
        State updated = state(session, frozen);
        ObjectNode response = json.object();
        response.put("issuedId", issuedId);
        response.put("optionCode", optionCode);
        response.put("answerRevision", answerRevision);
        response.put("revision", session.getRevision());
        response.put("effective", question.getEffective());
        response.put("stage", stageNumber(session.getStage()));
        response.put("rewound", rewind);
        ObjectNode branch = response.putObject("branchChanges");
        rewound.addAll(changes.excludedIssuedIds());
        branch.set("excludedIssuedIds", json.tree(rewound));
        branch.set("restoredIssuedIds", json.tree(changes.restoredIssuedIds()));
        branch.put("pendingQuestionCount", updated.plan().toIssue().size());
        branch.put("budgetLimited", updated.plan().budgetLimited());
        response.set("progress", progress(session, frozen, updated));
        response.put("nextAction", nextAction(session, updated));
        saved.storeMutationResponse(json.write(response));
        return success(request, 200, response);
    }

    @Transactional
    public Reply advance(Long userId, long sessionId, String key, long expectedRevision) {
        validateKey(key);
        users.requireActive(userId);
        ExplorationSession session = ownedLocked(userId, sessionId);
        policy.authorizeExisting(userId, session.getRunMode());
        ExplorationIdempotency request = reservation(userId, "ADVANCE", sessionId, key, json.hash(Map.of("expectedRevision", expectedRevision)));
        if (request.getResponseStatus() != null) return replay(request);
        writable(session, expectedRevision);
        var frozen = catalogs.load(sessionId);
        State current = state(session, frozen);
        if (!current.plan().toIssue().isEmpty() || current.issued().stream().anyMatch(q -> q.getStage().equals(session.getStage())
                && q.getEffective() && q.getRequired() && !current.answers().containsKey(q.getId())))
            throw new ExplorationApiException(422, "STAGE_INCOMPLETE", "현재 단계의 발급 및 필수 응답을 완료해 주세요.");
        if (!"STAGE_3".equals(session.getStage())) {
            QuestionBranchEngine engine = new QuestionBranchEngine(frozen.branch());
            var fixed = "STAGE_1".equals(session.getStage())
                    ? engine.freezeCandidates(responses(current), history(current))
                    : stages.load(sessionId, "STAGE_2", frozen).candidates();
            session.moveToStage("STAGE_1".equals(session.getStage()) ? "STAGE_2" : "STAGE_3");
            stages.enter(session, frozen, fixed, responses(current), history(current));
            reconcile(session, frozen); // restore only matching, previously issued identities; do not expose new ones
        }
        State updated = state(session, frozen);
        ObjectNode response = json.object();
        response.set("session", sessionDto(session, frozen));
        response.set("progress", progress(session, frozen, updated));
        response.put("nextAction", nextAction(session, updated));
        response.set("fixedCandidates", fixedCandidates(session, frozen));
        return success(request, 200, response);
    }

    @Transactional
    public Reply abandon(Long userId, long sessionId, String key, long expectedRevision) {
        validateKey(key);
        users.requireActive(userId);
        ExplorationSession session = ownedLocked(userId, sessionId);
        policy.authorizeExisting(userId, session.getRunMode());
        ExplorationIdempotency request = reservation(userId, "ABANDON", sessionId, key, json.hash(Map.of("expectedRevision", expectedRevision)));
        if (request.getResponseStatus() != null) return replay(request);
        writable(session, expectedRevision);
        var frozen = catalogs.load(sessionId);
        session.abandon(LocalDateTime.now());
        return success(request, 200, sessionDto(session, frozen));
    }

    @Transactional
    public Reply complete(Long userId, long sessionId, String key, long expectedRevision) {
        validateKey(key);
        users.requireActive(userId);
        ExplorationSession session = ownedLocked(userId, sessionId);
        policy.authorizeExisting(userId, session.getRunMode());
        ExplorationIdempotency request = reservation(userId, "COMPLETE", sessionId, key, json.hash(Map.of("expectedRevision", expectedRevision)));
        if (session.getStatus() == ExplorationSessionStatus.completed)
            return success(request, 200, resultStore.response(requiredResult(session)));
        if (request.getResponseStatus() != null || results.findBySessionId(sessionId).isPresent()) throw ExplorationApiException.integrity();
        writable(session, expectedRevision);
        var frozen = catalogs.load(sessionId);
        State current = state(session, frozen);
        if (current.issued().stream().anyMatch(q -> q.getEffective() && q.getRequired() && !current.answers().containsKey(q.getId())))
            throw new ExplorationApiException(422, "STAGE_INCOMPLETE", "이미 발급된 필수 문항의 응답을 완료해 주세요.");
        if (!current.one().plan().toIssue().isEmpty() || current.one().plan().active().stream().anyMatch(k ->
                current.issued().stream().noneMatch(q -> key(q).equals(k) && q.getEffective() && current.answers().containsKey(q.getId()))))
            throw new ExplorationApiException(422, "STAGE_INCOMPLETE", "1단계 필수 응답을 완료해 주세요.");
        try {
            var engine = new QuestionBranchEngine(frozen.branch());
            var fixed = "STAGE_1".equals(session.getStage()) ? engine.freezeCandidates(responses(current), history(current))
                    : stages.load(sessionId, "STAGE_2", frozen).candidates();
            boolean boundary = true; // Every already-exposed required question above is answered; partial batches never qualify.
            boolean early = !"STAGE_3".equals(session.getStage()) && engine.canExitAtModuleBoundary(fixed, responses(current), history(current),
                    QuestionBranchEngine.Presentation.none(), boundary);
            boolean globalLimit = current.issued().size() >= frozen.payload().path("rules").path("caps").path("pre_result").asInt();
            boolean terminal = "STAGE_3".equals(session.getStage()) && current.plan().toIssue().isEmpty();
            if (!early && !terminal && !globalLimit) throw new ExplorationApiException(409, "COMPLETION_NOT_ALLOWED", "아직 종료 조건을 충족하지 않았습니다.");
            var plans = json.array();
            plans.add(json.tree(current.one().plan()));
            boolean limited = current.one().plan().budgetLimited();
            if (stageNumber(session.getStage()) >= 2) {
                var two = stages.load(sessionId, "STAGE_2", frozen); plans.add(json.tree(two.plan())); limited |= two.plan().budgetLimited();
            }
            if (stageNumber(session.getStage()) >= 3) {
                var three = stages.load(sessionId, "STAGE_3", frozen); plans.add(json.tree(three.plan())); limited |= three.plan().budgetLimited();
            }
            String reason = early ? "EARLY_EXIT" : (globalLimit || limited ? "INFO_LIMIT_REACHED" : "NORMAL");
            ObjectNode input = json.object();
            input.put("resultContractVersion", ExplorationResultAssembler.CONTRACT);
            input.put("bundleHash", frozen.hash());
            input.put("completionReason", reason);
            input.set("fixedCandidates", json.tree(fixed));
            input.set("stagePlans", plans);
            var questions = input.putArray("questions");
            for (var q : current.issued()) {
                var row = questions.addObject(); row.set("key", json.tree(key(q))); row.put("ordinal", q.getOrdinal());
                row.put("stage", q.getStage()); row.put("required", q.getRequired()); row.put("effective", q.getEffective());
                row.put("snapshotHash", json.hex(json.read(q.getSnapshotJson())));
                row.put("optionCode", current.answers().get(q.getId()));
            }
            var now = LocalDateTime.now();
            ObjectNode snapshot = resultAssembler.assemble(sessionId, now.toString(), reason, frozen, fixed, responses(current), input);
            ObjectNode flags = snapshot.putObject("flags");
            flags.put("lowDifferentiation", current.one().lowDifferentiation());
            flags.put("interestTie", !current.one().tieAxes().isEmpty());
            flags.put("stateUnknown", "STATE_UNKNOWN".equals(current.one().route().reason()));
            flags.put("stateInconsistent", "STATE_INCONSISTENT".equals(current.one().route().reason()));
            snapshot.put("runMode", session.getRunMode().name()); snapshot.put("testOnly", session.getRunMode().name().equals("DEV"));
            ExplorationResult saved = resultStore.save(sessionId, snapshot);
            session.complete(now);
            return success(request, 200, resultStore.response(saved));
        } catch (IllegalArgumentException e) { throw ExplorationApiException.integrity(); }
    }

    @Transactional(readOnly = true)
    public JsonNode resultBySession(Long userId, long sessionId) {
        var session = sessions.findById(sessionId).filter(s -> s.isOwnedBy(userId)).orElseThrow(this::notFound);
        policy.authorizeExisting(userId, session.getRunMode());
        if (session.getStatus() != ExplorationSessionStatus.completed) {
            if (results.findBySessionId(sessionId).isPresent()) throw ExplorationApiException.integrity();
            throw notFound();
        }
        return resultStore.response(requiredResult(session));
    }

    @Transactional(readOnly = true)
    public JsonNode resultById(Long userId, long resultId) {
        var result = results.findById(resultId).orElseThrow(this::notFound);
        var session = sessions.findById(result.getSessionId()).filter(s -> s.isOwnedBy(userId)).orElseThrow(this::notFound);
        policy.authorizeExisting(userId, session.getRunMode());
        if (session.getStatus() != ExplorationSessionStatus.completed) throw ExplorationApiException.integrity();
        return resultStore.response(result);
    }

    @Transactional(readOnly = true)
    public JsonNode get(Long userId, long sessionId) {
        ExplorationSession session = sessions.findById(sessionId).filter(s -> s.isOwnedBy(userId)).orElseThrow(this::notFound);
        policy.authorizeExisting(userId, session.getRunMode());
        var frozen = catalogs.load(sessionId);
        State state = state(session, frozen);
        ObjectNode response = json.object();
        response.set("session", sessionDto(session, frozen));
        response.set("fixedCandidates", fixedCandidates(session, frozen));
        ArrayNode rows = response.putArray("issued");
        for (ExplorationIssuedQuestion question : state.issued()) {
            ObjectNode row = rows.addObject();
            row.set("question", questionDto(question, frozen));
            row.put("stage", stageNumber(question.getStage()));
            row.put("answerCode", state.answers().get(question.getId()));
            row.set("answerRevision", json.tree(state.answerRevisions().get(question.getId())));
            row.put("effective", question.getEffective());
        }
        response.set("progress", progress(session, frozen, state));
        response.put("nextAction", nextAction(session, state));
        return response;
    }

    @Transactional(readOnly = true)
    public JsonNode list(Long userId, String type) {
        if (!ExplorationSession.DIAGNOSIS_TYPE.equals(type)) throw new ExplorationApiException(400, "INVALID_REQUEST", "지원하지 않는 진단 유형입니다.");
        policy.authorize(userId);
        ArrayNode response = json.array();
        for (ExplorationSession session : sessions.findByUserIdOrderByStartedAtDesc(userId)) {
            policy.authorizeExisting(userId, session.getRunMode());
            ObjectNode summary = response.addObject();
            summary.put("sessionId", session.getId());
            summary.put("type", session.getDiagnosisType());
            summary.put("status", session.getStatus().name());
            summary.put("stage", stageNumber(session.getStage()));
            summary.set("startedAt", json.tree(session.getStartedAt()));
            summary.set("completedAt", json.tree(session.getCompletedAt()));
            summary.set("resultId", json.tree(resultId(session)));
        }
        return response;
    }

    private State state(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen) {
        List<ExplorationIssuedQuestion> history = issued.findBySessionIdOrderByOrdinalAsc(session.getId());
        Map<Long, String> current = new HashMap<>();
        Map<Long, Integer> revisions = new HashMap<>();
        if (!history.isEmpty()) answers.findByIssuedIdInAndCurrentTrue(history.stream().map(ExplorationIssuedQuestion::getId).toList())
                .forEach(a -> { current.put(a.getIssuedId(), a.getOptionCode()); revisions.put(a.getIssuedId(), a.getAnswerRevision()); });
        List<BranchCatalog.Answer> responses = history.stream().filter(q -> current.containsKey(q.getId()))
                .map(q -> new BranchCatalog.Answer(key(q), current.get(q.getId()), true, q.getEffective())).toList();
        try {
            for (ExplorationIssuedQuestion question : history) validatedSnapshot(question, frozen);
            responses.forEach(frozen.branch()::option); // excluded answers must also belong to their pinned variant
            var one = new QuestionBranchEngine(frozen.branch()).stageOne(responses, history.stream().map(this::key).toList());
            QuestionBranchEngine.Plan plan;
            if ("STAGE_1".equals(session.getStage())) plan = one.plan();
            else {
                var checkpoint = stages.load(session.getId(), session.getStage(), frozen);
                var selected = checkpoint.plan();
                var old = new HashSet<>(history.stream().map(this::key).toList());
                plan = new QuestionBranchEngine.Plan(selected.active(), selected.active().stream().filter(k -> !old.contains(k)).toList(),
                        selected.omitted(), selected.budgetLimited());
            }
            return new State(history, current, revisions, one, plan);
        } catch (IllegalArgumentException e) { throw ExplorationApiException.integrity(); }
    }

    private BranchChanges reconcile(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen) {
        State before = state(session, frozen);
        Set<BranchCatalog.Key> active = new HashSet<>(before.plan().active());
        List<Long> excluded = new ArrayList<>(), restored = new ArrayList<>();
        for (ExplorationIssuedQuestion question : before.issued()) {
            if (!question.getStage().equals(session.getStage())) continue;
            if (active.contains(key(question)) && !question.getEffective()) {
                question.restore(); restored.add(question.getId());
            } else if (!active.contains(key(question)) && question.getEffective()) {
                question.exclude(); excluded.add(question.getId());
            }
        }
        // No exposure here: fresh keys are issued only by /next; restored instances keep their original snapshot.
        issued.flush();
        return new BranchChanges(List.copyOf(excluded), List.copyOf(restored));
    }

    private JsonNode validatedSnapshot(ExplorationIssuedQuestion question, ExplorationRuntimeCatalog.Frozen frozen) {
        JsonNode snapshot = json.read(question.getSnapshotJson());
        if (!json.hex(snapshot).equals(json.hex(catalogs.questionSnapshot(frozen, key(question))))) throw ExplorationApiException.integrity();
        return snapshot;
    }

    private ObjectNode questionDto(ExplorationIssuedQuestion question, ExplorationRuntimeCatalog.Frozen frozen) {
        JsonNode snapshot = validatedSnapshot(question, frozen);
        ObjectNode dto = json.object();
        dto.put("issuedId", question.getId());
        dto.put("questionId", question.getQuestionId());
        dto.put("questionVersion", question.getQuestionVersion());
        dto.put("comparisonSet", question.getComparisonSet());
        dto.put("optionVariantVersion", question.getVariantVersion());
        dto.put("text", snapshot.path("question").path("question_text").asText());
        dto.put("required", question.getRequired());
        ArrayNode options = dto.putArray("options");
        List<JsonNode> ordered = new ArrayList<>();
        snapshot.path("options").forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(o -> o.path("option_order").asInt()));
        for (JsonNode option : ordered) {
            ObjectNode item = options.addObject();
            item.put("code", option.path("option_code").asText());
            item.put("text", option.path("option_text").asText());
            item.put("order", option.path("option_order").asInt());
        }
        return dto;
    }

    private ObjectNode sessionDto(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen) {
        ObjectNode response = json.object();
        response.put("sessionId", session.getId());
        response.put("diagnosisType", session.getDiagnosisType());
        response.put("runMode", session.getRunMode().name());
        response.put("testOnly", session.getRunMode().name().equals("DEV"));
        response.put("status", session.getStatus().name());
        response.put("stage", stageNumber(session.getStage()));
        response.put("revision", session.getRevision());
        response.set("versions", frozen.payload().path("versions").deepCopy());
        response.set("startedAt", json.tree(session.getStartedAt()));
        response.set("resultId", json.tree(resultId(session)));
        response.set("completedAt", json.tree(session.getCompletedAt()));
        return response;
    }

    private ObjectNode progress(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen, State state) {
        int maximum = frozen.payload().path("rules").path("caps").path("stage" + stageNumber(session.getStage())).asInt();
        if (maximum <= 0) throw ExplorationApiException.integrity();
        ObjectNode progress = json.object();
        progress.put("stage", stageNumber(session.getStage()));
        long shown = state.issued().stream().filter(q -> q.getStage().equals(session.getStage())).count();
        progress.put("answered", state.issued().stream().filter(q -> q.getStage().equals(session.getStage()) && q.getEffective() && state.answers().containsKey(q.getId())).count());
        progress.put("issued", shown);
        progress.put("stageMax", maximum);
        progress.put("remainingMax", Math.max(0, maximum - shown));
        progress.put("totalIssued", state.issued().size());
        return progress;
    }

    private String nextAction(ExplorationSession session, State state) {
        if (!session.isInProgress()) return null;
        if (state.issued().stream().anyMatch(q -> q.getStage().equals(session.getStage()) && q.getEffective() && !state.answers().containsKey(q.getId()))) return "ANSWER";
        if (!state.plan().toIssue().isEmpty()) return "NEXT";
        return "STAGE_3".equals(session.getStage()) ? "COMPLETE" : "ADVANCE";
    }

    private ExplorationIdempotency reservation(Long userId, String operation, long resourceId, String key, byte[] hash) {
        var existing = idempotency.findByUserIdAndOperationAndResourceIdAndIdempotencyKey(userId, operation, resourceId, key);
        if (existing.isPresent()) {
            if (!existing.get().hasSameRequest(hash)) throw new ExplorationApiException(409, "IDEMPOTENCY_CONFLICT", "같은 멱등 키의 요청 내용이 다릅니다.");
            if (existing.get().getResponseStatus() == null) throw ExplorationApiException.integrity();
            return existing.get();
        }
        return idempotency.save(ExplorationIdempotency.reserve(userId, operation, resourceId, key, hash));
    }

    private Reply replay(ExplorationIdempotency record) { return new Reply(record.getResponseStatus(), json.read(record.getResponseJson())); }
    private Reply success(ExplorationIdempotency request, int status, JsonNode response) {
        String saved = json.write(response);
        request.storeSuccess(status, saved);
        return new Reply(status, json.read(saved));
    }
    private ExplorationSession ownedLocked(Long userId, long sessionId) {
        return sessions.findByIdForUpdate(sessionId).filter(s -> s.isOwnedBy(userId)).orElseThrow(this::notFound);
    }
    private ExplorationApiException notFound() { return new ExplorationApiException(404, "RESOURCE_NOT_FOUND", "탐색 회차를 찾을 수 없습니다."); }
    private ExplorationResult requiredResult(ExplorationSession session) {
        return results.findBySessionId(session.getId()).orElseThrow(ExplorationApiException::integrity);
    }
    private Long resultId(ExplorationSession session) {
        if (session.getStatus() != ExplorationSessionStatus.completed) return null;
        var result = requiredResult(session); resultStore.response(result); return result.getId();
    }
    private void writable(ExplorationSession session, long expectedRevision) {
        if (session.getStatus() == ExplorationSessionStatus.completed) throw new ExplorationApiException(409, "SESSION_ALREADY_COMPLETED", "완료된 회차입니다.");
        if (session.getStatus() == ExplorationSessionStatus.abandoned) throw new ExplorationApiException(409, "SESSION_ABANDONED", "폐기된 회차입니다.");
        if (!session.hasRevision(expectedRevision)) throw new ExplorationApiException(409, "STALE_REVISION", "최신 회차를 조회한 뒤 다시 요청해 주세요.");
    }
    private static void validateKey(String key) {
        if (key == null || !key.matches("[!-~]{1,128}")) throw new ExplorationApiException(400, "INVALID_REQUEST", "멱등 키는 공백 없는 ASCII 문자 1~128자로 지정해 주세요.");
    }
    private List<BranchCatalog.Answer> responses(State state) {
        return state.issued().stream().filter(q -> state.answers().containsKey(q.getId()))
                .map(q -> new BranchCatalog.Answer(key(q), state.answers().get(q.getId()), true, q.getEffective())).toList();
    }
    private List<BranchCatalog.Key> history(State state) {
        return state.issued().stream().map(this::key).toList();
    }
    private JsonNode fixedCandidates(ExplorationSession session, ExplorationRuntimeCatalog.Frozen frozen) {
        if ("STAGE_1".equals(session.getStage())) return json.tree(null);
        var checkpoint = stages.load(session.getId(), "STAGE_2", frozen);
        ObjectNode dto = json.object();
        dto.put("checkpointId", checkpoint.id());
        dto.set("groupCodes", json.tree(checkpoint.candidates().groupCodes()));
        dto.set("majorCodes", json.tree(checkpoint.candidates().majorCodes()));
        return dto;
    }
    private static int stageNumber(String stage) { return Integer.parseInt(stage.substring("STAGE_".length())); }
    private static String nullable(String value) { return value.isEmpty() ? null : value; }
    private BranchCatalog.Key key(ExplorationIssuedQuestion question) {
        return new BranchCatalog.Key(question.getQuestionId(), question.getQuestionVersion(),
                Objects.requireNonNullElse(question.getComparisonSet(), ""), Objects.requireNonNullElse(question.getVariantVersion(), ""));
    }
}
