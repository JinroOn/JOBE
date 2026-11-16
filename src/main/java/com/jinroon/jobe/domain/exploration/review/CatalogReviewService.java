package com.jinroon.jobe.domain.exploration.review;

import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색형 공유 자료(카탈로그·실행 규칙·활동 카탈로그·활동)의 검토 이력을 기록한다.
 * 이력은 추가만 하고 수정·삭제하지 않는다. 같은 종류의 검토는 가장 최근 결정이 유효하다.
 * 검토자·시각·대상 버전/hash·사용 범위는 서버가 정한다. 요청 본문으로 바꿀 수 없다.
 */
@Service
@RequiredArgsConstructor
public class CatalogReviewService {

    private final JdbcTemplate jdbc;

    /** 검토 대상 유형. 이력의 target_type 값과 실제 테이블을 연결한다. */
    public enum TargetType {
        CATALOG("exploration_catalog"),
        RULESET("exploration_ruleset"),
        ACTIVITY_CATALOG("exploration_activity_catalog"),
        ACTIVITY("exploration_activity");

        private final String table;
        TargetType(String table) { this.table = table; }
    }

    /**
     * IMPLEMENTATION·DOMAIN은 개발(DEV) 사용 승인이며 대상의 검토 상태 열에 반영한다.
     * PILOT·RELEASE는 그 위의 사용 범위 게이트로, 이력만 남기고 상태 열은 바꾸지 않는다.
     */
    public enum ReviewKind {
        IMPLEMENTATION("DEV", "APPROVED_FOR_IMPLEMENTATION", "implementation_review_status"),
        DOMAIN("DEV", "APPROVED", "domain_review_status"),
        PILOT("PILOT", "APPROVED", null),
        RELEASE("PUBLIC", "APPROVED", null);

        private final String scope;
        private final String approvedValue;
        private final String statusColumn;
        ReviewKind(String scope, String approvedValue, String statusColumn) {
            this.scope = scope; this.approvedValue = approvedValue; this.statusColumn = statusColumn;
        }
    }

    public enum Decision { APPROVED, REJECTED }

    public record Target(String targetType, long targetId, String version, String contentHash, String contentStatus,
                         String implementationReviewStatus, String domainReviewStatus) {}

    public record Review(long reviewId, String reviewKind, String decision, String scope, long reviewerId,
                         LocalDateTime reviewedAt, String targetVersion, String targetHash, boolean matchesCurrentContent,
                         String evidenceReference) {}

    public record Recorded(Review review, Target target) {}

    public record History(Target target, List<Review> reviews) {}

    @Transactional
    public Recorded record(long reviewerId, TargetType type, long targetId, ReviewKind kind, Decision decision,
                           String evidenceReference) {
        Map<String, Object> row = lockedTarget(type, targetId);
        String contentStatus = (String) row.get("content_status");
        if ("ARCHIVED".equals(contentStatus)) throw new CustomException(ErrorCode.CATALOG_REVIEW_ARCHIVED);
        if (kind.statusColumn == null
                && !(Set.of("REVIEWED", "ACTIVE").contains(contentStatus) && developmentApproved(row))) {
            throw new CustomException(ErrorCode.CATALOG_REVIEW_PREREQUISITE);
        }

        String decisionValue = decision == Decision.APPROVED ? kind.approvedValue : "REJECTED";
        LocalDateTime reviewedAt = LocalDateTime.now();
        jdbc.update("INSERT INTO exploration_review_history(target_type,target_id,target_version,target_hash,review_kind,"
                        + "decision,reviewer_id,reviewed_at,evidence_reference,scope) VALUES (?,?,?,?,?,?,?,?,?,?)",
                type.name(), targetId, row.get("version"), row.get("content_hash"), kind.name(), decisionValue,
                reviewerId, reviewedAt, evidenceReference.strip(), kind.scope);
        long reviewId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        if (kind.statusColumn != null) {
            jdbc.update("UPDATE " + type.table + " SET " + kind.statusColumn + "=? WHERE id=?", decisionValue, targetId);
            row = lockedTarget(type, targetId);
            String next = nextContentStatus(row);
            if (!next.equals(row.get("content_status"))) {
                jdbc.update("UPDATE " + type.table + " SET content_status=? WHERE id=?", next, targetId);
            }
        }
        Map<String, Object> current = target(type, targetId, false);
        return new Recorded(review(reviewRow(reviewId), current), toTarget(type, current));
    }

    @Transactional(readOnly = true)
    public History history(TargetType type, long targetId) {
        Map<String, Object> current = target(type, targetId, false);
        List<Review> reviews = jdbc.queryForList("SELECT * FROM exploration_review_history WHERE target_type=? AND target_id=? "
                        + "ORDER BY reviewed_at DESC, id DESC", type.name(), targetId)
                .stream().map(r -> review(r, current)).toList();
        return new History(toTarget(type, current), reviews);
    }

    /**
     * 검토 결과에 따라 DRAFT와 REVIEWED만 오간다.
     * 구현 승인과 내용 승인(또는 해당 없음)이 모두 있으면 REVIEWED, 하나라도 반려되면 DRAFT로 되돌린다.
     * ACTIVE 전환·철회는 별도 활성화 절차에서 다룬다.
     */
    private String nextContentStatus(Map<String, Object> row) {
        String status = (String) row.get("content_status");
        boolean rejected = "REJECTED".equals(row.get("implementation_review_status"))
                || "REJECTED".equals(row.get("domain_review_status"));
        if ("DRAFT".equals(status) && developmentApproved(row)) return "REVIEWED";
        if ("REVIEWED".equals(status) && rejected) return "DRAFT";
        return status;
    }

    private boolean developmentApproved(Map<String, Object> row) {
        return "APPROVED_FOR_IMPLEMENTATION".equals(row.get("implementation_review_status"))
                && Set.of("APPROVED", "NOT_APPLICABLE").contains(row.get("domain_review_status"));
    }

    private Map<String, Object> lockedTarget(TargetType type, long targetId) {
        return target(type, targetId, true);
    }

    private Map<String, Object> target(TargetType type, long targetId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,version,content_hash,content_status,"
                + "implementation_review_status,domain_review_status FROM " + type.table + " WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), targetId);
        if (rows.isEmpty()) throw new CustomException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private Map<String, Object> reviewRow(long reviewId) {
        return jdbc.queryForMap("SELECT * FROM exploration_review_history WHERE id=?", reviewId);
    }

    private Target toTarget(TargetType type, Map<String, Object> row) {
        return new Target(type.name(), ((Number) row.get("id")).longValue(), (String) row.get("version"),
                (String) row.get("content_hash"), (String) row.get("content_status"),
                (String) row.get("implementation_review_status"), (String) row.get("domain_review_status"));
    }

    private Review review(Map<String, Object> row, Map<String, Object> current) {
        Object at = row.get("reviewed_at");
        LocalDateTime reviewedAt = at instanceof LocalDateTime t ? t : ((java.sql.Timestamp) at).toLocalDateTime();
        boolean matches = row.get("target_version").equals(current.get("version"))
                && row.get("target_hash").equals(current.get("content_hash"));
        return new Review(((Number) row.get("id")).longValue(), (String) row.get("review_kind"),
                (String) row.get("decision"), (String) row.get("scope"), ((Number) row.get("reviewer_id")).longValue(),
                reviewedAt, (String) row.get("target_version"), (String) row.get("target_hash"), matches,
                (String) row.get("evidence_reference"));
    }
}
