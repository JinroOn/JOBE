package com.jinroon.jobe.domain.exploration.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Explains immutable mappings and sealed responses; never invokes scoring or issues questions. */
@Component
@RequiredArgsConstructor
public class ResultComparisonProjector {
    private final ExplorationJson json;
    private final ExplorationRuntimeCatalog catalogs;

    public JsonNode overview(JsonNode result,ExplorationRuntimeCatalog.Frozen frozen) {
        ObjectNode out=base(result,frozen);var choices=out.putArray("candidateChoices");
        Set<String> seen=new HashSet<>();
        for(JsonNode candidate:result.path("rankedCandidates")) {
            String code=candidate.path("majorCode").asText();if(!seen.add(code))throw ExplorationApiException.integrity();
            JsonNode major=major(frozen,code);var item=choices.addObject();
            item.put("majorCode",code);item.put("majorName",major.path("major_name").asText());item.put("groupCode",major.path("exploration_group").asText());
        }
        out.put("available",choices.size()>=2);out.put("reasonCode",choices.size()>=2?"SELECT_TWO_CANDIDATES":"COMPARISON_TARGET_UNAVAILABLE");
        return out;
    }

    public JsonNode compare(JsonNode result,ExplorationRuntimeCatalog.Frozen frozen,String left,String right) {
        if(left.equals(right))throw new ExplorationApiException(400,"INVALID_REQUEST","서로 다른 두 후보를 선택해 주세요.");
        Set<String> candidates=new HashSet<>();result.path("rankedCandidates").forEach(m->candidates.add(m.path("majorCode").asText()));
        if(candidates.size()<2)throw new ExplorationApiException(409,"COMPARISON_TARGET_UNAVAILABLE","비교할 결과 후보가 두 개 이상 필요합니다.");
        if(!candidates.contains(left)||!candidates.contains(right))throw new ExplorationApiException(422,"MAJOR_NOT_IN_RESULT","고정된 결과 후보에서 비교할 전공을 선택해 주세요.");
        JsonNode a=major(frozen,left),b=major(frozen,right);ObjectNode out=base(result,frozen);
        out.put("available",true);out.set("left",card(a));out.set("right",card(b));
        var dimensions=out.putObject("dimensions");
        for(var dimension:Map.of("primaryInterestAxes","primary_interest_axes","secondaryInterestAxes","secondary_interest_axes","activityAxes","activity_axes","learningSets","learning_content_sets").entrySet()) {
            List<String> av=strings(a.path(dimension.getValue())),bv=strings(b.path(dimension.getValue()));
            var row=dimensions.putObject(dimension.getKey());
            row.set("common",json.tree(av.stream().filter(bv::contains).toList()));
            row.set("leftOnly",json.tree(av.stream().filter(x->!bv.contains(x)).toList()));
            row.set("rightOnly",json.tree(bv.stream().filter(x->!av.contains(x)).toList()));
        }
        var panels=out.putArray("comparisonSets");var skipped=out.putArray("skippedComparisonSets");
        var ap=frozen.branch().comparison(left);var bp=frozen.branch().comparison(right);
        for(String set:new TreeSet<>(ap.sets()))if(bp.sets().contains(set)) {
            Set<String> am=ap.memberships().get(set),bm=bp.memberships().get(set);
            if(am.equals(bm)||frozen.branch().skip(set,left,right)) {
                var row=skipped.addObject();row.put("comparisonSet",set);row.put("reasonCode","SAME_DIRECTION_MEMBERSHIP");
                row.put("declaredSkipPair",frozen.branch().skip(set,left,right));continue;
            }
            var variant=frozen.branch().variants().get(set);var panel=panels.addObject();panel.put("comparisonSet",set);panel.put("optionVariantVersion",variant.version());
            panel.set("leftDirections",directions(frozen,variant,am));panel.set("rightDirections",directions(frozen,variant,bm));
            var focus=panel.putArray("focusResponses");
            for(JsonNode answer:result.path("input").path("questions")) {
                JsonNode key=answer.path("key");
                if(!answer.path("effective").asBoolean() || !set.equals(key.path("comparisonSet").asText()))continue;
                BranchCatalog.Key identity=new BranchCatalog.Key(key.path("questionId").asText(),key.path("questionVersion").asText(),set,key.path("variantVersion").asText());
                JsonNode snapshot=catalogSnapshot(frozen,identity);
                if(!json.hex(snapshot).equals(answer.path("snapshotHash").asText()))throw ExplorationApiException.integrity();
                if(answer.path("optionCode").isNull()||answer.path("optionCode").isMissingNode())continue;
                var option=frozen.branch().option(new BranchCatalog.Answer(identity,answer.path("optionCode").asText(),true,true));
                JsonNode source=null;for(JsonNode o:snapshot.path("options"))if(o.path("option_code").asText().equals(option.code()))source=o;
                if(source==null)throw ExplorationApiException.integrity();
                var row=focus.addObject();row.put("questionId",identity.questionId());row.put("questionVersion",identity.questionVersion());
                row.put("answerText",source.path("option_text").asText());row.put("uncertain",option.unknown());row.put("selectedDirection",option.unknown()?null:option.meaning());
                row.put("usage","USER_EXPLORATION_FOCUS_ONLY");
            }
            panel.put("responseState",focus.isEmpty()?"NOT_ANSWERED":"RECORDED");
        }
        out.put("comparisonState",!panels.isEmpty()?"DISTINCT_DIRECTIONS":!skipped.isEmpty()?"SAME_DIRECTIONS":"NO_SHARED_COMPARISON_SET");
        out.put("message","고정된 매핑과 당시 응답을 나란히 확인하는 탐색 자료입니다. 전공의 우열이나 능력·합격 가능성을 판정하지 않습니다.");
        return out;
    }

    private ObjectNode base(JsonNode result,ExplorationRuntimeCatalog.Frozen frozen) {
        ObjectNode out=json.object();out.put("comparisonContractVersion","RESULT_COMPARISON_V1_0");out.put("sessionId",result.path("sessionId").asLong());
        out.put("runMode",result.path("runMode").asText());out.put("testOnly",true);out.put("usage","IMPLEMENTATION_MAPPING_DEV_ONLY");
        out.put("changesRecommendation",false);out.put("scoreEffect",0);out.set("versions",frozen.payload().path("versions").deepCopy());out.put("bundleHash",frozen.hash());return out;
    }
    private ObjectNode card(JsonNode source) {
        var card=json.object();
        for(var entry:Map.of("majorCode","major_code","majorName","major_name","groupCode","exploration_group","interestStructure","interest_structure","domainReviewStatus","domain_review_status","activityAxes","activity_axes","learningSets","learning_content_sets").entrySet())card.set(entry.getKey(),source.path(entry.getValue()).deepCopy());
        card.set("primaryInterestAxes",source.path("primary_interest_axes").deepCopy());card.set("secondaryInterestAxes",source.path("secondary_interest_axes").deepCopy());
        card.put("careerInformationStatus","NOT_PROVIDED");card.put("preparationInformationStatus","NOT_PROVIDED");return card;
    }
    private JsonNode directions(ExplorationRuntimeCatalog.Frozen frozen,BranchCatalog.Variant variant,Set<String> memberships) {
        var out=json.array();
        for(String direction:new TreeSet<>(memberships)) {
            var row=out.addObject();row.put("directionCode",direction);var descriptions=row.putArray("descriptions");
            for(String q:variant.questions()) {
                var snapshot=catalogSnapshot(frozen,frozen.branch().key(frozen.branch().question(q),variant.code()));
                for(JsonNode option:snapshot.path("options"))if(direction.equals(option.path("meaning_code").asText())) {
                    var text=descriptions.addObject();text.put("questionId",q);text.put("text",option.path("option_text").asText());
                }
            }
        }
        return out;
    }
    private JsonNode catalogSnapshot(ExplorationRuntimeCatalog.Frozen frozen,BranchCatalog.Key key) {
        return catalogs.questionSnapshot(frozen,key);
    }
    private JsonNode major(ExplorationRuntimeCatalog.Frozen frozen,String code){for(JsonNode m:frozen.payload().path("mapping").path("majors"))if(code.equals(m.path("major_code").asText()))return m;throw ExplorationApiException.integrity();}
    private List<String> strings(JsonNode value){List<String> out=new ArrayList<>();value.forEach(x->out.add(x.asText()));return out;}
}
