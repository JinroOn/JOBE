package com.jinroon.jobe.domain.exploration.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.exploration.service.*;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
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
    private final ExplorationResultRepository results;
    private final ExplorationResultStore resultStore;
    private final ExplorationRuntimeCatalog catalogs;
    private final PostResultSupportService support;
    private final ExplorationJson json;
    private final JdbcTemplate jdbc;
    @Value("${exploration.activities.catalog-version:ACTIVITY_CATALOG_V2_DRAFT}") private String selectedVersion;
    private record Candidate(JsonNode payload,String hash,int majorOrder,double preference,List<Long> reviews) {}

    /** Result GET refreshes only dynamic activity fields. Completion idempotency stays historical. */
    @Transactional(readOnly=true)
    public JsonNode enrich(long userId,long resultId,JsonNode dto) {
        var roots=jdbc.queryForList("SELECT * FROM exploration_activity_catalog WHERE version=?",selectedVersion);
        // Existing sealed results remain readable without a runtime catalog when no activity is usable.
        if(roots.isEmpty() || approvals(roots.get(0),"ACTIVITY_CATALOG").isEmpty())return dto;
        JsonNode activities=get(userId,resultId);ObjectNode updated=dto.deepCopy();
        for(String field:List.of("nextActivities","activityAvailability","activityMessage"))updated.set(field,activities.path(field));
        return updated;
    }

    @Transactional(readOnly=true)
    public JsonNode get(long userId,long resultId) {
        // Includes authentication scope, ownership, completed state and seal validation; no writes.
        JsonNode state=support.get(userId,resultId);
        var result=results.findById(resultId).orElseThrow(()->ExplorationApiException.integrity());
        JsonNode snapshot=resultStore.verifiedSnapshot(result);
        var frozen=catalogs.load(result.getSessionId());
        if(!frozen.hash().equals(snapshot.path("bundleHash").asText()))throw ExplorationApiException.integrity();
        ObjectNode response=json.object();response.put("resultId",resultId);response.put("supportRevision",state.path("supportRevision").asLong());
        response.put("targetMajorCode",state.path("targetMajorCode").isMissingNode()?null:state.path("targetMajorCode").textValue());
        response.put("runMode","DEV");response.put("testOnly",true);response.put("changesRecommendation",false);
        response.put("selectionPolicyVersion","ACTIVITY_SELECTION_V1");response.put("maximum",3);
        response.put("catalogVersion",selectedVersion);response.putNull("catalogHash");response.put("resultInputHash",snapshot.path("inputHash").asText());
        response.set("versions",snapshot.path("versions").deepCopy());response.set("nextActivities",json.array());
        var roots=jdbc.queryForList("SELECT * FROM exploration_activity_catalog WHERE version=?",selectedVersion);
        if(roots.isEmpty())return unavailable(response,"NO_ACTIVITY_CATALOG");
        var root=roots.get(0);JsonNode payload=checked(root);
        response.put("catalogHash",root.get("content_hash").toString());
        if(!payload.path("version").asText().equals(selectedVersion) || !payload.path("major_mapping_version").asText().equals(root.get("major_mapping_version")))throw ExplorationApiException.integrity();
        if(!root.get("major_mapping_version").equals(snapshot.path("versions").path("majorMapping").asText()))return unavailable(response,"MAPPING_VERSION_UNAVAILABLE");
        List<Long> rootReviews=approvals(root,"ACTIVITY_CATALOG");
        if(rootReviews.isEmpty())return unavailable(response,"NO_APPROVED_ACTIVITIES");
        Map<String,JsonNode> embedded=new HashMap<>();payload.path("activities").forEach(a->embedded.put(a.path("activity_id").asText(),a));
        var rows=jdbc.queryForList("SELECT * FROM exploration_activity WHERE catalog_id=? ORDER BY activity_code",number(root,"id"));
        if(rows.size()!=embedded.size())throw ExplorationApiException.integrity();
        Map<String,Integer> majorOrder=new HashMap<>();int index=0;
        String target=state.path("targetMajorCode").textValue();
        for(JsonNode major:snapshot.path("rankedCandidates"))majorOrder.put(major.path("majorCode").asText(),index++);
        Map<String,Integer> preferences=new HashMap<>();
        for(JsonNode issued:state.path("issued"))if(issued.path("answerApplicable").asBoolean()) {
            String q=issued.path("question").path("questionId").asText();
            if(!ActivityCatalogLoader.PREFERENCES.contains(q))continue;
            var key=frozen.branch().key(frozen.branch().question(q),"");
            frozen.branch().options(key).stream().filter(o->o.code().equals(issued.path("answerCode").asText())&&!o.unknown()&&o.value()!=null).findFirst()
                    .ifPresent(o->preferences.put(q,o.value()));
        }
        List<Candidate> eligible=new ArrayList<>();
        for(var row:rows) {
            JsonNode item=checked(row);JsonNode original=embedded.get(row.get("activity_code").toString());
            if(original==null || !json.hex(original).equals(row.get("content_hash"))
                    || !item.path("activity_id").asText().equals(row.get("activity_code"))
                    || !item.path("version").asText().equals(row.get("version"))
                    || !Objects.equals(item.path("major_code").textValue(),row.get("major_code")))throw ExplorationApiException.integrity();
            List<Long> reviews=approvals(row,"ACTIVITY");if(reviews.isEmpty())continue;
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

    /** Latest decision wins even if it has another hash/scope or explicitly rejects the old approval. */
    private List<Long> approvals(Map<String,Object> row,String type) {
        if(!Set.of("REVIEWED","ACTIVE").contains(row.get("content_status")) || !"APPROVED_FOR_IMPLEMENTATION".equals(row.get("implementation_review_status")) || !"APPROVED".equals(row.get("domain_review_status")))return List.of();
        List<Long> ids=new ArrayList<>();
        for(String kind:List.of("IMPLEMENTATION","DOMAIN")) {
            var reviews=jdbc.queryForList("SELECT * FROM exploration_review_history WHERE target_type=? AND target_id=? AND target_version=? AND review_kind=? ORDER BY reviewed_at DESC,id DESC LIMIT 1",type,number(row,"id"),row.get("version"),kind);
            if(reviews.isEmpty())return List.of();var review=reviews.get(0);
            if(!Objects.equals(review.get("target_hash"),row.get("content_hash")) || !"DEV".equals(review.get("scope"))
                    || !(kind.equals("IMPLEMENTATION")?"APPROVED_FOR_IMPLEMENTATION":"APPROVED").equals(review.get("decision"))
                    || number(review,"reviewer_id")<=0 || review.get("evidence_reference")==null || review.get("evidence_reference").toString().isBlank()
                    || reviewedAt(review).isAfter(java.time.LocalDateTime.now()))return List.of();
            ids.add(number(review,"id"));
        }
        return ids;
    }
    private JsonNode checked(Map<String,Object> row){JsonNode payload=json.read(row.get("payload_json").toString());if(!json.hex(payload).equals(row.get("content_hash")))throw ExplorationApiException.integrity();return payload;}
    private java.time.LocalDateTime reviewedAt(Map<String,Object> row){Object value=row.get("reviewed_at");return value instanceof java.time.LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime();}
    private JsonNode unavailable(ObjectNode response,String reason){response.put("activityAvailability","UNAVAILABLE");response.put("reasonCode",reason);response.put("activityMessage","승인된 탐색 활동 안내를 준비 중입니다.");return response;}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
