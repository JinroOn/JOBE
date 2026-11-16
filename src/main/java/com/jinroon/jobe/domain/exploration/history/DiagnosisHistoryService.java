package com.jinroon.jobe.domain.exploration.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.repository.ExplorationResultRepository;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiagnosisHistoryService {
    private final JdbcTemplate jdbc;
    private final HistoryCursorCodec cursors;
    private final ExplorationRuntimePolicy policy;
    private final ExplorationResultRepository results;
    private final ExplorationResultStore resultStore;
    private final ExplorationJson json;

    private record Row(long itemId, long sessionId, int rank, String type, String status, int stage,
                       LocalDateTime startedAt, LocalDateTime completedAt, LocalDateTime sortAt, Long resultId,
                       long resultCount, String resultStatus, String contract, String runMode) {}
    private record Links(String session, String resume, String result) {}
    private record Item(String key, String sessionKey, long sessionId, String type, String status, int stage,
                        LocalDateTime startedAt, LocalDateTime completedAt, LocalDateTime occurredAt,
                        String resultKey, Long resultId, String resultAvailability, String resultStatus,
                        String resultContractVersion, String resultUnavailableReason, String runMode, boolean testOnly, String nextAction, Links links) {}

    @Transactional(readOnly = true)
    public JsonNode list(long userId, String view, HistoryQuery query) {
        if (!Set.of("sessions", "results").contains(view)) throw HistoryQuery.invalid();
        if (view.equals("results") && !Set.of("ALL", "completed").contains(query.status())) throw HistoryQuery.invalid();
        var mode = policy.authorize(userId); // This new namespace stays isolated DEV; legacy endpoints are unchanged.
        HistoryCursorCodec.State cursor = query.cursor() == null ? null : cursors.decode(query.cursor(), userId, view, query);
        boolean legacy = !query.type().equals("EXPLORATION_V1"), exploration = !query.type().equals("LEGACY");
        long legacyFence = cursor == null ? legacy ? fence(userId, view, false, mode) : 0 : cursor.legacyFence();
        long explorationFence = cursor == null ? exploration ? fence(userId, view, true, mode) : 0 : cursor.explorationFence();
        List<String> sources = new ArrayList<>(); List<Object> args = new ArrayList<>();
        if (legacy) { sources.add(source(view, false)); addSourceArgs(args, userId, legacyFence, view, false, mode); }
        if (exploration) { sources.add(source(view, true)); addSourceArgs(args, userId, explorationFence, view, true, mode); }
        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(String.join(" UNION ALL ", sources)).append(") history WHERE 1=1");
        if (!query.status().equals("ALL")) { sql.append(" AND status=?"); args.add(query.status()); }
        if (cursor != null) {
            sql.append(" AND (sort_at<? OR (sort_at=? AND (type_rank<? OR (type_rank=? AND item_id<?))))");
            args.add(cursor.sortAt()); args.add(cursor.sortAt());
            args.add(cursor.rank()); args.add(cursor.rank()); args.add(cursor.id());
        }
        sql.append(" ORDER BY sort_at DESC,type_rank DESC,item_id DESC LIMIT ?"); args.add(query.limit()+1);
        List<Row> rows = jdbc.query(sql.toString(), this::read, args.toArray());
        boolean more = rows.size() > query.limit();
        List<Row> visible = rows.subList(0, Math.min(rows.size(), query.limit()));
        List<Item> items = visible.stream().map(row -> project(userId, view, row)).toList();
        String next = null;
        if (more) {
            Row last = visible.get(visible.size()-1);
            next = cursors.encode(new HistoryCursorCodec.State(1,userId,view,query.type(),query.status(),query.limit(),
                    legacyFence,explorationFence,last.sortAt(),last.rank(),last.itemId()));
        }
        var response = json.object(); response.set("items", json.tree(items)); response.put("hasMore", more);
        response.put("nextCursor", next); response.put("limit", query.limit());
        response.put("runMode", mode.name()); response.put("testOnly", mode == ExplorationRunMode.DEV);
        var filters=response.putObject("filters");filters.put("type",query.type());filters.put("status",query.status());
        return response;
    }

    private long fence(long userId, String view, boolean exploration, ExplorationRunMode mode) {
        String session = exploration ? "exploration_session" : "diagnosis_sessions";
        String table = exploration ? "exploration_result" : "diagnosis_results";
        String fk = exploration ? "session_id" : "diagnosis_session_id";
        String sql = view.equals("sessions") ? "SELECT COALESCE(MAX(s.id),0) FROM "+session+" s WHERE s.user_id=?"
                : "SELECT COALESCE(MAX(r.id),0) FROM "+table+" r JOIN "+session+" s ON s.id=r."+fk+" WHERE s.user_id=? AND s.status='completed'";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (!view.equals("sessions") && !exploration) { sql += " AND r.user_id=?"; args.add(userId); }
        if (exploration) { sql += " AND s.run_mode=?"; args.add(mode.name()); }
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args.toArray()));
    }

    private String source(String view, boolean exploration) {
        String session = exploration ? "exploration_session" : "diagnosis_sessions";
        String result = exploration ? "exploration_result" : "diagnosis_results";
        String fk = exploration ? "session_id" : "diagnosis_session_id";
        String base = "s.id session_id,"+(exploration ? "1 type_rank,'EXPLORATION_V1'" : "0 type_rank,'LEGACY'")+" diagnosis_type,s.status,"
                +(exploration ? "CAST(SUBSTRING(s.stage,7) AS UNSIGNED)" : "s.current_step")+" stage,s.started_at,s.completed_at,"
                +(exploration ? "s.run_mode" : "NULL")+" run_mode,";
        if (view.equals("sessions")) {
            if (exploration) return "SELECT s.id item_id,"+base+"s.started_at sort_at,r.id result_id,IF(r.id IS NULL,0,1) result_count,r.result_status,r.result_contract_version FROM "
                    +session+" s LEFT JOIN "+result+" r ON r.session_id=s.id WHERE s.user_id=? AND s.id<=? AND s.run_mode=?";
            return "SELECT s.id item_id,"+base+"s.started_at sort_at,r.result_id,COALESCE(r.result_count,0) result_count,NULL result_status,NULL result_contract_version FROM "
                    +session+" s LEFT JOIN (SELECT diagnosis_session_id,MIN(id) result_id,COUNT(*) result_count FROM diagnosis_results WHERE user_id=? GROUP BY diagnosis_session_id) r "
                    +"ON r.diagnosis_session_id=s.id WHERE s.user_id=? AND s.id<=?";
        }
        return "SELECT r.id item_id,"+base+"COALESCE(s.completed_at,r.created_at,s.started_at) sort_at,r.id result_id,1 result_count,"
                +(exploration ? "r.result_status,r.result_contract_version" : "NULL result_status,NULL result_contract_version")
                +" FROM "+result+" r JOIN "+session+" s ON s.id=r."+fk+" WHERE s.user_id=? AND r.id<=? AND s.status='completed'"
                +(exploration ? " AND s.run_mode=?" : " AND r.user_id=?");
    }

    private void addSourceArgs(List<Object> args,long userId,long fence,String view,boolean exploration,ExplorationRunMode mode) {
        if (view.equals("sessions") && !exploration) args.add(userId);
        args.add(userId); args.add(fence);
        if (exploration) args.add(mode.name()); else if (view.equals("results")) args.add(userId);
    }

    private Item project(long userId,String view,Row row) {
        boolean completed = row.status().equals("completed");
        boolean available = completed && row.resultCount()==1;
        boolean corrupt = false;
        if (row.rank()==1) {
            if (row.stage()<1 || row.stage()>3) throw ExplorationApiException.integrity();
            policy.authorizeExisting(userId, ExplorationRunMode.valueOf(row.runMode()));
            if (!completed && row.resultCount()!=0) throw ExplorationApiException.integrity();
            if (available) {
                var result = results.findById(row.resultId()).filter(r -> r.getSessionId().equals(row.sessionId())).orElse(null);
                available = result != null && resultStore.availableForListing(result);
                corrupt = !available;
            }
        }
        Long resultId = available ? row.resultId() : null;
        String sessionPath = row.rank()==0 ? "/api/diagnoses/sessions/"+row.sessionId() : "/api/exploration/v1/sessions/"+row.sessionId();
        String resultPath = resultId==null ? null : (row.rank()==0 ? "/api/results/" : "/api/exploration/v1/results/")+resultId;
        String availability = corrupt ? "UNAVAILABLE" : !completed ? "NOT_COMPLETED" : row.resultCount()==0 ? "MISSING" : row.resultCount()>1 ? "AMBIGUOUS" : "AVAILABLE";
        String action = row.status().equals("in_progress") ? "RESUME" : available ? "VIEW_RESULT" : "NONE";
        return new Item(row.type()+":"+(view.equals("sessions")?"SESSION":"RESULT")+":"+row.itemId(),row.type()+":SESSION:"+row.sessionId(),row.sessionId(),row.type(),row.status(),row.stage(),
                row.startedAt(),row.completedAt(),row.sortAt(),resultId==null?null:row.type()+":RESULT:"+resultId,resultId,
                availability,available?row.resultStatus():null,available?row.contract():null,corrupt?"CATALOG_INTEGRITY_ERROR":null,row.runMode(),row.rank()==1 && row.runMode().equals("DEV"),
                action,new Links(sessionPath,action.equals("RESUME")?sessionPath:null,resultPath));
    }

    private Row read(ResultSet rs,int number) throws SQLException {
        Long rid = rs.getObject("result_id")==null ? null : rs.getLong("result_id");
        return new Row(rs.getLong("item_id"),rs.getLong("session_id"),rs.getInt("type_rank"),rs.getString("diagnosis_type"),rs.getString("status"),
                rs.getInt("stage"),time(rs,"started_at"),time(rs,"completed_at"),time(rs,"sort_at"),rid,rs.getLong("result_count"),
                rs.getString("result_status"),rs.getString("result_contract_version"),rs.getString("run_mode"));
    }
    // DATETIME is a wall-clock value, not an instant in the JVM's default zone.
    private static LocalDateTime time(ResultSet rs,String column) throws SQLException { return rs.getObject(column,LocalDateTime.class); }
}
