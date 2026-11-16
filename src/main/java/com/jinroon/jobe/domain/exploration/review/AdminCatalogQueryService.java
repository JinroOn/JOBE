package com.jinroon.jobe.domain.exploration.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.review.CatalogActivationService.Scope;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.TargetType;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면에서 검토·활성화할 대상을 고르는 목록과 상세.
 * 동작 가능 여부는 검토·활성화 API와 같은 규칙으로 계산하며, 조회만 하고 데이터를 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminCatalogQueryService {

    private static final List<TargetType> ROOTS = List.of(TargetType.CATALOG, TargetType.RULESET, TargetType.ACTIVITY_CATALOG);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CatalogReviewService reviews;
    private final CatalogActivationService activations;

    public record CatalogSummary(String targetType, long targetId, String catalogType, String version, String contentStatus,
                                 String implementationReviewStatus, String domainReviewStatus, String contentHash,
                                 Integer itemCount, LocalDateTime createdAt, boolean usedByRuntime,
                                 List<String> allowedActions, Map<String, String> blockedReasons) {}

    public record CatalogQuestion(String questionId, String questionVersion, String stage, String moduleCode,
                                  String metricCode, String text, String contentStatus, String implementationReviewStatus,
                                  String domainReviewStatus) {}

    public record CatalogDetail(CatalogSummary catalog, List<CatalogReviewService.Review> reviews,
                                List<CatalogActivationService.Activation> activations, List<CatalogQuestion> questions) {}

    @Transactional(readOnly = true)
    public List<CatalogSummary> list(long adminId, TargetType filterType, String contentStatus) {
        if (filterType == TargetType.ACTIVITY) throw new CustomException(ErrorCode.INVALID_INPUT);
        List<CatalogSummary> rows = new ArrayList<>();
        for (TargetType type : ROOTS) {
            if (filterType != null && filterType != type) continue;
            for (Map<String, Object> row : rows(type, null)) {
                if (contentStatus != null && !contentStatus.equals(row.get("content_status"))) continue;
                rows.add(summary(adminId, type, row));
            }
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public CatalogDetail detail(long adminId, TargetType type, long targetId) {
        if (type == TargetType.ACTIVITY) throw new CustomException(ErrorCode.INVALID_INPUT);
        List<Map<String, Object>> found = rows(type, targetId);
        if (found.isEmpty()) throw new CustomException(ErrorCode.NOT_FOUND);
        Map<String, Object> row = found.get(0);
        List<CatalogQuestion> questions = type == TargetType.CATALOG && "QUESTION_CATALOG".equals(row.get("catalog_type"))
                ? questions(targetId) : List.of();
        return new CatalogDetail(summary(adminId, type, row), reviews.history(type, targetId).reviews(),
                activations.history(type, targetId).activations(), questions);
    }

    private CatalogSummary summary(long adminId, TargetType type, Map<String, Object> row) {
        List<String> allowed = new ArrayList<>();
        Map<String, String> blocked = new LinkedHashMap<>();
        if ("ARCHIVED".equals(row.get("content_status"))) blocked.put("REVIEW", ErrorCode.CATALOG_REVIEW_ARCHIVED.name());
        else allowed.add("REVIEW");
        ErrorCode activation = activations.activationBlocker(adminId, type, row, Scope.DEV);
        if (activation == null) allowed.add("ACTIVATE");
        else blocked.put("ACTIVATE", activation.name());
        ErrorCode archive = activations.archiveBlocker(type, row);
        if (archive == null) allowed.add("ARCHIVE");
        else blocked.put("ARCHIVE", archive.name());
        String catalogType = type == TargetType.CATALOG ? (String) row.get("catalog_type") : type.name();
        return new CatalogSummary(type.name(), number(row, "id"), catalogType, (String) row.get("version"),
                (String) row.get("content_status"), (String) row.get("implementation_review_status"),
                (String) row.get("domain_review_status"), (String) row.get("content_hash"), itemCount(type, row),
                time(row.get("created_at")), activations.usedByRuntime(type, row), allowed, blocked);
    }

    private List<Map<String, Object>> rows(TargetType type, Long id) {
        String columns = "id,version,content_hash,content_status,implementation_review_status,domain_review_status";
        String extra = switch (type) {
            case CATALOG -> ",catalog_type,created_at,metadata_json";
            case RULESET -> ",created_at";
            default -> "";
        };
        String order = type == TargetType.CATALOG ? " ORDER BY catalog_type, id" : " ORDER BY id";
        return id == null
                ? jdbc.queryForList("SELECT " + columns + extra + " FROM " + type.table() + order)
                : jdbc.queryForList("SELECT " + columns + extra + " FROM " + type.table() + " WHERE id=?", id);
    }

    /** 카탈로그에 속한 항목 수. 개수 개념이 없는 자료(의미 계약·실행 규칙)는 null이다. */
    private Integer itemCount(TargetType type, Map<String, Object> row) {
        long id = number(row, "id");
        if (type == TargetType.ACTIVITY_CATALOG) return count("exploration_activity", id);
        if (type == TargetType.RULESET) return null;
        return switch ((String) row.get("catalog_type")) {
            case "QUESTION_CATALOG" -> count("exploration_question", id);
            case "OPTION_CATALOG" -> count("exploration_option", id);
            case "MAJOR_MAPPING" -> count("exploration_major_mapping", id);
            case "OPTION_VARIANT" -> variantCount(row.get("metadata_json"));
            default -> null;
        };
    }

    private Integer count(String table, long catalogId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE catalog_id=?", Integer.class, catalogId);
    }

    private Integer variantCount(Object metadata) {
        if (metadata == null) return null;
        try {
            JsonNode variants = mapper.readTree(metadata.toString()).path("variants");
            return variants.isArray() ? variants.size() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private List<CatalogQuestion> questions(long catalogId) {
        return jdbc.queryForList("SELECT question_id,question_version,stage,module_code,metric_code,content_status,"
                        + "implementation_review_status,domain_review_status,"
                        + "JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.question_text')) AS question_text "
                        + "FROM exploration_question WHERE catalog_id=? ORDER BY stage, question_id", catalogId)
                .stream()
                .map(r -> new CatalogQuestion((String) r.get("question_id"), (String) r.get("question_version"),
                        (String) r.get("stage"), (String) r.get("module_code"), (String) r.get("metric_code"),
                        (String) r.get("question_text"), (String) r.get("content_status"),
                        (String) r.get("implementation_review_status"), (String) r.get("domain_review_status")))
                .toList();
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static LocalDateTime time(Object value) {
        if (value == null) return null;
        return value instanceof LocalDateTime t ? t : ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
