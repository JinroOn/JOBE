package com.jinroon.jobe.domain.exploration.history;

import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public record HistoryQuery(String type, String status, int limit, String cursor) {
    public static HistoryQuery parse(MultiValueMap<String, String> params) {
        Set<String> allowed = Set.of("type", "status", "limit", "cursor");
        if (params.entrySet().stream().anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().size() != 1)) throw invalid();
        String type = params.getFirst("type"), status = params.getFirst("status"), size = params.getFirst("limit");
        type = type == null ? "ALL" : type; status = status == null ? "ALL" : status;
        if (!Set.of("ALL", "LEGACY", "EXPLORATION_V1").contains(type)
                || !Set.of("ALL", "in_progress", "completed", "abandoned").contains(status)) throw invalid();
        int limit = 20;
        if (size != null) {
            if (!size.matches("[1-9][0-9]{0,2}")) throw invalid();
            limit = Integer.parseInt(size);
            if (limit > 100) throw invalid();
        }
        return new HistoryQuery(type, status, limit, params.getFirst("cursor"));
    }
    public static ExplorationApiException invalid() {
        return new ExplorationApiException(400, "INVALID_REQUEST", "이력 조회 유형·상태·페이지 크기를 확인해 주세요.");
    }
}
