package com.jinroon.jobe.domain.exploration.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.TargetType;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색형 공유 자료의 ACTIVE 전환과 보관(철회).
 * 같은 자리(카탈로그는 같은 catalog_type, 실행 규칙·활동 카탈로그는 각 테이블 전체)에는 ACTIVE가 하나만 있다.
 * 새 대상을 활성화하면 기존 ACTIVE를 같은 트랜잭션에서 ARCHIVED로 내린다.
 * 실행 자료는 ACTIVE 여부가 아니라 설정의 버전으로 선택한다. 그래서 설정이 사용 중인 실행 자료는 보관하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CatalogActivationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Value("${exploration.runtime.ruleset-version:RULESET_V1_5_IMPLEMENTATION_CANDIDATE}")
    private String rulesetVersion;

    @Value("${exploration.activities.catalog-version:ACTIVITY_CATALOG_V1_DRAFT}")
    private String activityCatalogVersion;

    /** 활성화 범위. 범위가 넓을수록 필요한 검토 종류가 늘어난다. */
    public enum Scope {
        DEV(List.of("IMPLEMENTATION", "DOMAIN")),
        PILOT(List.of("IMPLEMENTATION", "DOMAIN", "PILOT")),
        PUBLIC(List.of("IMPLEMENTATION", "DOMAIN", "PILOT", "RELEASE"));

        private final List<String> requiredKinds;
        Scope(List<String> requiredKinds) { this.requiredKinds = requiredKinds; }
    }

    public record Activation(long activationId, String action, String targetType, long targetId, String targetVersion,
                             String targetHash, String scope, long actorId, Long relatedTargetId, List<Long> reviewIds,
                             String reason, LocalDateTime createdAt) {}

    public record State(String targetType, long targetId, String version, String contentStatus) {}

    public record Result(Activation activation, State target, List<State> archived) {}

    public record History(State target, List<Activation> activations) {}

    @Transactional
    public Result activate(long actorId, TargetType type, long targetId, Scope scope, String reason) {
        rootOnly(type);
        Map<String, Object> target = row(type, targetId, false);
        lockSlot(type, target);
        target = row(type, targetId, true);
        List<Long> reviewIds = checkActivatable(actorId, type, target, scope);
        List<Map<String, Object>> previous = previousActive(type, target);
        LocalDateTime now = LocalDateTime.now();
        List<State> archived = new ArrayList<>();
        for (Map<String, Object> old : previous) {
            long oldId = id(old);
            jdbc.update("UPDATE " + type.table() + " SET content_status='ARCHIVED' WHERE id=?", oldId);
            log(type, old, "ARCHIVE", null, actorId, targetId, null, "새 활성 대상으로 교체: " + reason.strip(), now);
            archived.add(state(type, row(type, oldId, false)));
        }
        jdbc.update("UPDATE " + type.table() + " SET content_status='ACTIVE' WHERE id=?", targetId);
        Long related = previous.isEmpty() ? null : id(previous.get(0));
        long activationId = log(type, target, "ACTIVATE", scope.name(), actorId, related, reviewIds, reason.strip(), now);
        return new Result(activation(activationRow(activationId)), state(type, row(type, targetId, false)), archived);
    }

    /**
     * 철회. 활동 카탈로그는 보관하면 추천이 '제공 불가'로 바뀔 뿐이라 사용 중이어도 허용한다.
     * 카탈로그·실행 규칙은 사용 중이면 새 회차 시작이 막히므로 설정을 먼저 바꾼 뒤 보관한다.
     */
    @Transactional
    public Result archive(long actorId, TargetType type, long targetId, String reason) {
        rootOnly(type);
        Map<String, Object> target = row(type, targetId, true);
        ErrorCode blocker = archiveBlocker(type, target);
        if (blocker != null) throw new CustomException(blocker);
        jdbc.update("UPDATE " + type.table() + " SET content_status='ARCHIVED' WHERE id=?", targetId);
        long activationId = log(type, target, "ARCHIVE", null, actorId, null, null, reason.strip(), LocalDateTime.now());
        return new Result(activation(activationRow(activationId)), state(type, row(type, targetId, false)), List.of());
    }

    @Transactional(readOnly = true)
    public History history(TargetType type, long targetId) {
        rootOnly(type);
        Map<String, Object> target = row(type, targetId, false);
        List<Activation> rows = jdbc.queryForList("SELECT * FROM exploration_catalog_activation WHERE target_type=? AND target_id=? "
                + "ORDER BY created_at DESC, id DESC", type.name(), targetId).stream().map(this::activation).toList();
        return new History(state(type, target), rows);
    }

    /**
     * 관리자 목록 화면용. 지금 이 관리자가 활성화하면 막히는 이유(오류 코드)를 돌려주고, 가능하면 null이다.
     * 실제 활성화와 같은 규칙을 쓰며 데이터를 바꾸지 않는다.
     */
    ErrorCode activationBlocker(long actorId, TargetType type, Map<String, Object> target, Scope scope) {
        if (type == TargetType.ACTIVITY) return ErrorCode.INVALID_INPUT;
        try {
            checkActivatable(actorId, type, target, scope);
            previousActive(type, target);
            return null;
        } catch (CustomException e) {
            return e.getErrorCode();
        }
    }

    /** 관리자 목록 화면용. 보관하면 막히는 이유를 돌려주고, 가능하면 null이다. */
    ErrorCode archiveBlocker(TargetType type, Map<String, Object> target) {
        if (type == TargetType.ACTIVITY) return ErrorCode.INVALID_INPUT;
        if ("ARCHIVED".equals(target.get("content_status"))) return ErrorCode.CATALOG_ALREADY_ARCHIVED;
        if (type != TargetType.ACTIVITY_CATALOG && inUse(type, target)) return ErrorCode.CATALOG_IN_USE;
        return null;
    }

    /** 현재 실행 설정이 이 자료를 고르는지. */
    boolean usedByRuntime(TargetType type, Map<String, Object> target) {
        return inUse(type, target);
    }

    private List<Long> checkActivatable(long actorId, TargetType type, Map<String, Object> target, Scope scope) {
        if (!"REVIEWED".equals(target.get("content_status"))) {
            throw new CustomException(ErrorCode.CATALOG_ACTIVATION_NOT_ALLOWED);
        }
        return approvals(type, target, scope, actorId);
    }

    /** 자리를 넘겨줄 기존 ACTIVE. 실행 설정이 쓰는 자료면 교체할 수 없다. */
    private List<Map<String, Object>> previousActive(TargetType type, Map<String, Object> target) {
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT * FROM " + type.table() + " WHERE content_status='ACTIVE'"
                + slotCondition(type) + " AND id<>?", slotArgs(type, target, id(target)));
        for (Map<String, Object> old : previous) {
            if (inUse(type, old)) throw new CustomException(ErrorCode.CATALOG_IN_USE);
        }
        return previous;
    }

    /**
     * 범위에 필요한 검토 종류마다 가장 최근 결정이 현재 버전·hash에 대한 승인이어야 한다.
     * 내용 검토가 '해당 없음'인 자료는 DOMAIN 이력을 요구하지 않는다. 승인한 관리자는 활성화할 수 없다.
     */
    private List<Long> approvals(TargetType type, Map<String, Object> target, Scope scope, long actorId) {
        if (!"APPROVED_FOR_IMPLEMENTATION".equals(target.get("implementation_review_status"))
                || !Set.of("APPROVED", "NOT_APPLICABLE").contains(target.get("domain_review_status"))) {
            throw new CustomException(ErrorCode.CATALOG_APPROVAL_MISSING);
        }
        List<Long> ids = new ArrayList<>();
        for (String kind : scope.requiredKinds) {
            if (kind.equals("DOMAIN") && "NOT_APPLICABLE".equals(target.get("domain_review_status"))) continue;
            List<Map<String, Object>> latest = jdbc.queryForList("SELECT * FROM exploration_review_history WHERE target_type=? "
                    + "AND target_id=? AND review_kind=? ORDER BY reviewed_at DESC, id DESC LIMIT 1", type.name(), id(target), kind);
            String approved = kind.equals("IMPLEMENTATION") ? "APPROVED_FOR_IMPLEMENTATION" : "APPROVED";
            if (latest.isEmpty() || !approved.equals(latest.get(0).get("decision"))
                    || !target.get("version").equals(latest.get(0).get("target_version"))
                    || !target.get("content_hash").equals(latest.get(0).get("target_hash"))) {
                throw new CustomException(ErrorCode.CATALOG_APPROVAL_MISSING);
            }
            if (((Number) latest.get(0).get("reviewer_id")).longValue() == actorId) {
                throw new CustomException(ErrorCode.CATALOG_SELF_APPROVAL);
            }
            ids.add(id(latest.get(0)));
        }
        return ids;
    }

    /** 현재 실행 설정이 고르는 실행 규칙, 그 규칙이 가리키는 카탈로그, 설정된 활동 카탈로그. */
    private boolean inUse(TargetType type, Map<String, Object> row) {
        return switch (type) {
            case RULESET -> rulesetVersion.equals(row.get("version"));
            case ACTIVITY_CATALOG -> activityCatalogVersion.equals(row.get("version"));
            case CATALOG -> runtimeCatalogs().contains(row.get("catalog_type") + ":" + row.get("version"));
            case ACTIVITY -> false;
        };
    }

    private Set<String> runtimeCatalogs() {
        List<String> rules = jdbc.queryForList("SELECT rules_json FROM exploration_ruleset WHERE version=?", String.class, rulesetVersion);
        Set<String> keys = new HashSet<>();
        if (rules.isEmpty()) return keys;
        try {
            JsonNode root = mapper.readTree(rules.get(0));
            JsonNode versions = root.path("catalog_versions");
            keys.add("QUESTION_CATALOG:" + versions.path("question").asText());
            keys.add("OPTION_CATALOG:" + versions.path("option").asText());
            keys.add("OPTION_VARIANT:" + versions.path("comparison_option_variant").asText());
            keys.add("MAJOR_MAPPING:" + versions.path("major_mapping").asText());
            keys.add("SEMANTIC_CONTRACT:" + root.path("semantic_contract_version").asText());
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return keys;
    }

    private void rootOnly(TargetType type) {
        // 개별 활동은 활동 카탈로그 단위로 활성화한다. 개별 활동의 철회는 내용 검토 반려로 처리한다.
        if (type == TargetType.ACTIVITY) throw new CustomException(ErrorCode.INVALID_INPUT);
    }

    /** 같은 자리의 행을 id 순서로 잠가 동시 활성화를 직렬화한다. */
    private void lockSlot(TargetType type, Map<String, Object> target) {
        jdbc.queryForList("SELECT id FROM " + type.table() + " WHERE 1=1" + slotCondition(type) + " ORDER BY id FOR UPDATE",
                slotArgs(type, target));
    }

    private String slotCondition(TargetType type) {
        return type == TargetType.CATALOG ? " AND catalog_type=?" : "";
    }

    private Object[] slotArgs(TargetType type, Map<String, Object> target, Object... extra) {
        List<Object> args = new ArrayList<>();
        if (type == TargetType.CATALOG) args.add(target.get("catalog_type"));
        args.addAll(List.of(extra));
        return args.toArray();
    }

    Map<String, Object> row(TargetType type, long targetId, boolean lock) {
        String columns = "id,version,content_hash,content_status,implementation_review_status,domain_review_status"
                + (type == TargetType.CATALOG ? ",catalog_type" : "");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + columns + " FROM " + type.table() + " WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), targetId);
        if (rows.isEmpty()) throw new CustomException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private long log(TargetType type, Map<String, Object> target, String action, String scope, long actorId, Long related,
                     List<Long> reviewIds, String reason, LocalDateTime at) {
        String ids;
        try { ids = reviewIds == null ? null : mapper.writeValueAsString(reviewIds); }
        catch (IOException e) { throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR); }
        jdbc.update("INSERT INTO exploration_catalog_activation(target_type,target_id,target_version,target_hash,action,scope,"
                        + "actor_id,related_target_id,review_ids,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                type.name(), id(target), target.get("version"), target.get("content_hash"), action, scope, actorId, related,
                ids, reason, at);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Map<String, Object> activationRow(long activationId) {
        return jdbc.queryForMap("SELECT * FROM exploration_catalog_activation WHERE id=?", activationId);
    }

    private Activation activation(Map<String, Object> row) {
        Object at = row.get("created_at");
        LocalDateTime createdAt = at instanceof LocalDateTime t ? t : ((java.sql.Timestamp) at).toLocalDateTime();
        List<Long> reviewIds = List.of();
        if (row.get("review_ids") != null) {
            try { reviewIds = List.of(mapper.readValue(row.get("review_ids").toString(), Long[].class)); }
            catch (IOException e) { throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR); }
        }
        Object related = row.get("related_target_id");
        return new Activation(id(row), (String) row.get("action"), (String) row.get("target_type"),
                ((Number) row.get("target_id")).longValue(), (String) row.get("target_version"), (String) row.get("target_hash"),
                (String) row.get("scope"), ((Number) row.get("actor_id")).longValue(),
                related == null ? null : ((Number) related).longValue(), reviewIds, (String) row.get("reason"), createdAt);
    }

    private State state(TargetType type, Map<String, Object> row) {
        return new State(type.name(), id(row), (String) row.get("version"), (String) row.get("content_status"));
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }
}
