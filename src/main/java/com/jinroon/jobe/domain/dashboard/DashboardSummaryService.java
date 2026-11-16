package com.jinroon.jobe.domain.dashboard;

import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.repository.ExplorationResultRepository;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 첫 화면용 요약. 레거시 진단과 탐색형 진단을 함께 보고 최신 완료 결과와 진행 중 회차를 고른다.
 * 탐색형을 쓸 수 없는 사용자(기능 꺼짐, 실행 범위 밖)에게도 실패하지 않고 레거시 정보만 돌려준다.
 * 결과 본문은 넣지 않는다. 화면은 상세 경로로 각 결과를 조회한다.
 */
@Service
@RequiredArgsConstructor
public class DashboardSummaryService {

    static final String LEGACY = "LEGACY";
    static final String EXPLORATION = "EXPLORATION_V1";
    /** 손상 결과를 건너뛸 때 확인할 최대 개수. 정상 사용에서는 첫 건에서 끝난다. */
    private static final int EXPLORATION_SCAN_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final ExplorationRuntimePolicy explorationPolicy;
    private final ExplorationResultRepository explorationResults;
    private final ExplorationResultStore explorationResultStore;

    @Value("${exploration.enabled:false}")
    private boolean explorationEnabled;

    public record ResultRef(String type, long resultId, long sessionId, String resultStatus, LocalDateTime completedAt,
                            String path) {}

    public record SessionRef(String type, long sessionId, String stage, LocalDateTime startedAt, String path) {}

    public record Summary(ResultRef latestResult, Map<String, ResultRef> latestByType, List<SessionRef> inProgress,
                          SessionRef resume, String exploration) {}

    @Transactional(readOnly = true)
    public Summary summary(long userId) {
        ExplorationRunMode mode = explorationMode(userId);

        ResultRef legacy = latestLegacyResult(userId);
        ResultRef exploration = mode == null ? null : latestExplorationResult(userId, mode);
        Map<String, ResultRef> byType = new LinkedHashMap<>();
        byType.put(LEGACY, legacy);
        byType.put(EXPLORATION, exploration);
        ResultRef latest = java.util.stream.Stream.of(legacy, exploration).filter(Objects::nonNull)
                .max(Comparator.comparing(ResultRef::completedAt).thenComparingLong(ResultRef::resultId))
                .orElse(null);

        List<SessionRef> inProgress = new ArrayList<>();
        SessionRef legacySession = legacyInProgress(userId);
        if (legacySession != null) inProgress.add(legacySession);
        if (mode != null) {
            SessionRef explorationSession = explorationInProgress(userId, mode);
            if (explorationSession != null) inProgress.add(explorationSession);
        }
        inProgress.sort(Comparator.comparing(SessionRef::startedAt).reversed());
        SessionRef resume = inProgress.isEmpty() ? null : inProgress.get(0);

        return new Summary(latest, byType, inProgress, resume, mode == null ? "UNAVAILABLE" : "AVAILABLE");
    }

    /** 탐색형 사용 가능 여부. 쓸 수 없으면 null이며 오류로 바꾸지 않는다. */
    private ExplorationRunMode explorationMode(long userId) {
        if (!explorationEnabled) return null;
        try {
            return explorationPolicy.authorize(userId);
        } catch (ExplorationApiException e) {
            return null;
        }
    }

    /** 레거시 결과의 완료 시각은 회차 완료 시각, 없으면 결과 생성 시각이다. */
    private ResultRef latestLegacyResult(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.id, r.diagnosis_session_id, "
                + "COALESCE(s.completed_at, r.created_at) AS completed_at FROM diagnosis_results r "
                + "LEFT JOIN diagnosis_sessions s ON s.id = r.diagnosis_session_id "
                + "WHERE r.user_id = ? ORDER BY completed_at DESC, r.id DESC LIMIT 1", userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long id = number(row, "id");
        return new ResultRef(LEGACY, id, number(row, "diagnosis_session_id"), null, time(row, "completed_at"),
                "/api/results/" + id);
    }

    /** 손상된(봉인 불일치) 결과는 건너뛰고 그다음 완료 결과를 고른다. */
    private ResultRef latestExplorationResult(long userId, ExplorationRunMode mode) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.id, s.id AS session_id, s.completed_at "
                + "FROM exploration_session s JOIN exploration_result r ON r.session_id = s.id "
                + "WHERE s.user_id = ? AND s.run_mode = ? AND s.status = 'completed' "
                + "ORDER BY s.completed_at DESC, r.id DESC LIMIT " + EXPLORATION_SCAN_LIMIT, userId, mode.name());
        for (Map<String, Object> row : rows) {
            long id = number(row, "id");
            var result = explorationResults.findById(id).orElse(null);
            if (result == null || !explorationResultStore.availableForListing(result)) continue;
            return new ResultRef(EXPLORATION, id, number(row, "session_id"), result.getResultStatus().name(),
                    time(row, "completed_at"), "/api/exploration/v1/results/" + id);
        }
        return null;
    }

    private SessionRef legacyInProgress(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, current_step, started_at FROM diagnosis_sessions "
                + "WHERE user_id = ? AND status = 'in_progress' ORDER BY started_at DESC, id DESC LIMIT 1", userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long id = number(row, "id");
        Object step = row.get("current_step");
        return new SessionRef(LEGACY, id, step == null ? null : step.toString(), time(row, "started_at"),
                "/api/diagnoses/sessions/" + id);
    }

    private SessionRef explorationInProgress(long userId, ExplorationRunMode mode) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, stage, started_at FROM exploration_session "
                + "WHERE user_id = ? AND run_mode = ? AND status = 'in_progress' ORDER BY started_at DESC, id DESC LIMIT 1",
                userId, mode.name());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long id = number(row, "id");
        return new SessionRef(EXPLORATION, id, (String) row.get("stage"), time(row, "started_at"),
                "/api/exploration/v1/sessions/" + id);
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static LocalDateTime time(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) return null;
        return value instanceof LocalDateTime t ? t : ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
