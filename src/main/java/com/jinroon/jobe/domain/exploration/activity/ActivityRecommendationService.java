package com.jinroon.jobe.domain.exploration.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.entity.ExplorationResult;
import com.jinroon.jobe.domain.exploration.entity.ExplorationSession;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection. Shared content gates are checked on every request, including revocation. */
@Service
@RequiredArgsConstructor
public class ActivityRecommendationService {
    private final PostResultSupportService support;
    private final ExplorationJson json;
    private final JdbcTemplate jdbc;
    /** 설정이 없을 때 쓰는 활동 카탈로그 버전. 활성화 관리(CatalogActivationService)도 같은 값을 기준으로 삼는다. */
    public static final String DEFAULT_CATALOG_VERSION = "ACTIVITY_CATALOG_V2_DRAFT";
    @Value("${exploration.activities.catalog-version:" + DEFAULT_CATALOG_VERSION + "}") private String selectedVersion;
    private record Candidate(JsonNode payload,String hash,int majorOrder,double preference,List<Long> reviews) {}
    private record ReviewKey(long targetId, String kind) {}

    /** Result GET refreshes only dynamic activity fields. Completion idempotency stays historical. */
    @Transactional(readOnly=true)
    public JsonNode enrich(ExplorationSession session, ExplorationResult result, JsonNode verifiedSnapshot, JsonNode dto) {
        var root = selectedCatalog();
        // Existing sealed results remain readable without a runtime catalog when no activity is usable.
        if (root == null) return dto;
        var reviews = catalogApprovals(root);
        if (reviews.isEmpty()) return dto;
        JsonNode activities = recommend(result.getId(), support.activityInput(session, result, verifiedSnapshot), root, reviews);
        ObjectNode updated = dto.deepCopy();
        for(String field:List.of("nextActivities","activityAvailability","activityMessage"))updated.set(field,activities.path(field));
        return updated;
    }

    @Transactional(readOnly=true)
    public JsonNode get(long userId,long resultId) {
        return recommend(resultId, support.activityInput(userId, resultId), null, null);
    }

    private JsonNode recommend(long resultId, PostResultSupportService.ActivityInput input,
                               Map<String, Object> approvedRoot, List<Long> approvedRootReviews) {
        JsonNode state = input.state();
        JsonNode snapshot = input.snapshot();
        ObjectNode response=json.object();response.put("resultId",resultId);response.put("supportRevision",state.path("supportRevision").asLong());
        response.put("targetMajorCode",state.path("targetMajorCode").isMissingNode()?null:state.path("targetMajorCode").textValue());
        response.put("runMode","DEV");response.put("testOnly",true);response.put("changesRecommendation",false);
        response.put("selectionPolicyVersion","ACTIVITY_SELECTION_V1");response.put("maximum",3);
        response.put("catalogVersion",selectedVersion);response.putNull("catalogHash");response.put("resultInputHash",snapshot.path("inputHash").asText());
        response.set("versions",snapshot.path("versions").deepCopy());response.set("nextActivities",json.array());
        var root = approvedRoot == null ? selectedCatalog() : approvedRoot;
        if(root==null)return unavailable(response,"NO_ACTIVITY_CATALOG");
        JsonNode payload=checked(root);
        response.put("catalogHash",root.get("content_hash").toString());
        if(!payload.path("version").asText().equals(selectedVersion) || !payload.path("major_mapping_version").asText().equals(root.get("major_mapping_version")))throw ExplorationApiException.integrity();
        if(!root.get("major_mapping_version").equals(snapshot.path("versions").path("majorMapping").asText()))return unavailable(response,"MAPPING_VERSION_UNAVAILABLE");
        List<Long> rootReviews = approvedRootReviews == null ? catalogApprovals(root) : approvedRootReviews;
        if(rootReviews.isEmpty())return unavailable(response,"NO_APPROVED_ACTIVITIES");
        Map<String,JsonNode> embedded=new HashMap<>();payload.path("activities").forEach(a->embedded.put(a.path("activity_id").asText(),a));
        var rows = activityRows(root);
        if (rows.size() != embedded.size() || rows.isEmpty()
                || number(rows.get(0), "actual_count") != embedded.size()) throw ExplorationApiException.integrity();
        var activityReviews = latestReviews("""
                JOIN exploration_activity a ON a.id = r.target_id AND a.version = r.target_version
                WHERE r.target_type = 'ACTIVITY' AND a.catalog_id = ?
                  AND a.content_status IN ('REVIEWED', 'ACTIVE')
                  AND a.implementation_review_status = 'APPROVED_FOR_IMPLEMENTATION'
                  AND a.domain_review_status = 'APPROVED'
                """, number(root, "id"));
        var now = LocalDateTime.now();
        Map<String,Integer> majorOrder=new HashMap<>();int index=0;
        String target=state.path("targetMajorCode").textValue();
        for(JsonNode major:snapshot.path("rankedCandidates"))majorOrder.put(major.path("majorCode").asText(),index++);
        Map<String,Integer> preferences=new HashMap<>();
        for(JsonNode issued:state.path("issued"))if(issued.path("answerApplicable").asBoolean()) {
            String q=issued.path("question").path("questionId").asText();
            if(!ActivityCatalogLoader.PREFERENCES.contains(q))continue;
            var key=input.branch().key(input.branch().question(q),"");
            input.branch().options(key).stream().filter(o->o.code().equals(issued.path("answerCode").asText())&&!o.unknown()&&o.value()!=null).findFirst()
                    .ifPresent(o->preferences.put(q,o.value()));
        }
        List<Candidate> eligible=new ArrayList<>();
        for(var row:rows) {
            if (row.get("id") == null || !bodyMatches(row)) throw ExplorationApiException.integrity();
            JsonNode item = embedded.get(row.get("activity_code").toString());
            if (item == null || !json.hex(item).equals(row.get("content_hash"))
                    || !item.path("activity_id").asText().equals(row.get("activity_code"))
                    || !item.path("version").asText().equals(row.get("version"))
                    || !Objects.equals(item.path("major_code").textValue(),row.get("major_code")))throw ExplorationApiException.integrity();
            List<Long> reviews=approvals(row,activityReviews,now);if(reviews.isEmpty())continue;
            try{ActivityCatalogLoader.validateActivity(item);}catch(IllegalArgumentException e){throw ExplorationApiException.integrity();}
            String major=item.path("major_code").textValue();
            // No candidates: approved general activities only. A target narrows, never adds a major.
            if(major!=null && (target!=null?!target.equals(major):!majorOrder.containsKey(major)))continue;
            int order=major==null?Integer.MAX_VALUE:majorOrder.getOrDefault(major,Integer.MAX_VALUE);
            double score=0;int n=0;
            for(JsonNode tag:item.path("preference_question_ids"))if(preferences.containsKey(tag.asText())){score+=preferences.get(tag.asText())-50;n++;}
            eligible.add(new Candidate(item,row.get("content_hash").toString(),order,n==0?0:score/n,reviews));
        }
        eligible.sort(Comparator.comparingInt(Candidate::majorOrder).thenComparing(Comparator.comparingDouble(Candidate::preference).reversed())
                .thenComparing(c->c.payload().path("activity_id").asText()));
        var activities=response.putArray("nextActivities");
        for(Candidate item:eligible.stream().limit(3).toList()) {
            ObjectNode card=activities.addObject();
            for(String field:List.of("activity_id","version","major_code","title","kind","body_markdown","steps","artifacts","example_output","worksheet_markdown","alternative","duration_minutes_proposal","requirements","sources"))
                card.set(field,item.payload().path(field).deepCopy());
            card.put("contentHash",item.hash());card.set("reviewIds",json.tree(item.reviews()));
            card.put("selectionReason",item.payload().path("major_code").isNull()?"GENERAL_EXPLORATION":target!=null?"SELECTED_TARGET":"FIXED_RESULT_CANDIDATE");
            card.put("preferenceApplied",item.preference()!=0);
        }
        response.set("catalogReviewIds",json.tree(rootReviews));
        if(activities.isEmpty())return unavailable(response,"NO_MATCHING_APPROVED_ACTIVITIES");
        response.put("activityAvailability","AVAILABLE");response.put("reasonCode","APPROVED_ACTIVITIES_AVAILABLE");
        response.put("activityMessage","검토된 탐색 활동입니다. 활동 결과는 원본 전공 추천을 변경하지 않습니다.");
        return response;
    }

    private Map<String,Object> selectedCatalog() {
        var rows = jdbc.queryForList("""
                SELECT c.*, SHA2(CAST(c.payload_json AS BINARY), 256) AS body_fingerprint
                FROM exploration_activity_catalog c WHERE c.version=?
                """, selectedVersion);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> activityRows(Map<String, Object> root) {
        long catalogId = number(root, "id");
        // Use the same catalog body as the Java seal check, even if the database uses READ COMMITTED.
        return jdbc.queryForList("""
                SELECT a.id, a.activity_code, a.major_code, a.version, a.content_hash,
                       a.content_status, a.implementation_review_status, a.domain_review_status,
                       totals.actual_count,
                       CAST(a.payload_json AS BINARY) = CAST(expected.body AS BINARY) AS body_matches
                FROM exploration_activity_catalog c
                JOIN JSON_TABLE(c.payload_json, '$.activities[*]' COLUMNS (
                    activity_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin PATH '$.activity_id',
                    body JSON PATH '$' ERROR ON ERROR
                )) expected
                LEFT JOIN exploration_activity a ON a.catalog_id = c.id AND a.activity_code = expected.activity_code
                CROSS JOIN (
                    SELECT COUNT(*) AS actual_count FROM exploration_activity WHERE catalog_id = ?
                ) totals
                WHERE c.id = ? AND SHA2(CAST(c.payload_json AS BINARY), 256) = ?
                ORDER BY expected.activity_code
                """, catalogId, catalogId, root.get("body_fingerprint"));
    }

    private boolean bodyMatches(Map<String, Object> row) {
        Object match = row.get("body_matches");
        return Boolean.TRUE.equals(match) || match instanceof Number number && number.intValue() == 1;
    }

    private List<Long> catalogApprovals(Map<String,Object> root) {
        if (!reviewable(root)) return List.of();
        return approvals(root, latestReviews("""
                WHERE r.target_type = 'ACTIVITY_CATALOG' AND r.target_id = ? AND r.target_version = ?
                """, number(root, "id"), root.get("version")), LocalDateTime.now());
    }

    private Map<ReviewKey, Map<String,Object>> latestReviews(String targets, Object... args) {
        var rows = jdbc.queryForList("""
                SELECT id, target_id, target_hash, review_kind, decision, reviewer_id, reviewed_at, evidence_reference, scope
                FROM (
                    SELECT r.*, ROW_NUMBER() OVER (
                        PARTITION BY r.target_id, r.target_version, r.review_kind
                        ORDER BY r.reviewed_at DESC, r.id DESC
                    ) AS review_rank
                    FROM exploration_review_history r
                """ + targets + """
                    AND r.review_kind IN ('IMPLEMENTATION', 'DOMAIN')
                ) ranked
                WHERE review_rank = 1
                """, args);
        Map<ReviewKey, Map<String,Object>> latest = new HashMap<>();
        for (var row : rows) latest.put(new ReviewKey(number(row, "target_id"), row.get("review_kind").toString()), row);
        return latest;
    }

    private boolean reviewable(Map<String,Object> row) {
        return Set.of("REVIEWED","ACTIVE").contains(row.get("content_status"))
                && "APPROVED_FOR_IMPLEMENTATION".equals(row.get("implementation_review_status"))
                && "APPROVED".equals(row.get("domain_review_status"));
    }

    /** Select the latest decision before checking hash, scope or approval. Never fall back to an older decision. */
    private List<Long> approvals(Map<String,Object> row, Map<ReviewKey, Map<String,Object>> latest, LocalDateTime now) {
        if (!reviewable(row)) return List.of();
        List<Long> ids=new ArrayList<>();
        for(String kind:List.of("IMPLEMENTATION","DOMAIN")) {
            var review = latest.get(new ReviewKey(number(row, "id"), kind));
            if (review == null) return List.of();
            if(!Objects.equals(review.get("target_hash"),row.get("content_hash")) || !"DEV".equals(review.get("scope"))
                    || !(kind.equals("IMPLEMENTATION")?"APPROVED_FOR_IMPLEMENTATION":"APPROVED").equals(review.get("decision"))
                    || number(review,"reviewer_id")<=0 || review.get("evidence_reference")==null || review.get("evidence_reference").toString().isBlank()
                    || reviewedAt(review).isAfter(now))return List.of();
            ids.add(number(review,"id"));
        }
        return ids;
    }
    private JsonNode checked(Map<String,Object> row){JsonNode payload=json.read(row.get("payload_json").toString());if(!json.hex(payload).equals(row.get("content_hash")))throw ExplorationApiException.integrity();return payload;}
    private java.time.LocalDateTime reviewedAt(Map<String,Object> row){Object value=row.get("reviewed_at");return value instanceof java.time.LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime();}
    private JsonNode unavailable(ObjectNode response,String reason){response.put("activityAvailability","UNAVAILABLE");response.put("reasonCode",reason);response.put("activityMessage","승인된 탐색 활동 안내를 준비 중입니다.");return response;}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
