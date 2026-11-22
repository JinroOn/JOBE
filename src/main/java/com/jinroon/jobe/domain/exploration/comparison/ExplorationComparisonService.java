package com.jinroon.jobe.domain.exploration.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.entity.*;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.exploration.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExplorationComparisonService {
    private final ExplorationSessionRepository sessions;
    private final ExplorationResultRepository results;
    private final ExplorationIssuedQuestionRepository issued;
    private final ExplorationRuntimePolicy policy;
    private final ExplorationRuntimeCatalog catalogs;
    private final ExplorationResultStore resultStore;
    private final ResultComparisonProjector projector;
    private final ExplorationJson json;

    @Transactional(readOnly=true)
    public JsonNode result(long userId,long resultId,String left,String right) {
        var result=results.findById(resultId).orElseThrow(ExplorationComparisonService::notFound);
        var session=owned(userId,result.getSessionId());
        if(session.getStatus()!=ExplorationSessionStatus.completed)throw ExplorationApiException.integrity();
        JsonNode snapshot=resultStore.verifiedSnapshot(result);var frozen=catalogs.load(session.getId());
        if(!frozen.hash().equals(snapshot.path("bundleHash").asText()) || !frozen.hash().equals(snapshot.path("input").path("bundleHash").asText())
                || !json.hex(frozen.payload().path("versions")).equals(json.hex(snapshot.path("versions"))))throw ExplorationApiException.integrity();
        try {
            ObjectNode response=(ObjectNode)(left==null?projector.overview(snapshot,frozen):projector.compare(snapshot,frozen,left,right));
            response.put("resultId",resultId);return response;
        }catch(IllegalArgumentException e){throw ExplorationApiException.integrity();}
    }

    @Transactional(readOnly=true)
    public JsonNode options(long userId,long sessionId,String set) {
        var session=owned(userId,sessionId);var frozen=catalogs.load(sessionId);
        if(!frozen.branch().variants().containsKey(set))throw new ExplorationApiException(422,"UNKNOWN_COMPARISON_SET","고정 카탈로그에 없는 비교 세트입니다.");
        var out=json.object();out.put("sessionId",sessionId);out.put("revision",session.getRevision());out.put("comparisonSet",set);
        out.put("optionVariantVersion",frozen.branch().variants().get(set).version());out.put("runMode",session.getRunMode().name());out.put("testOnly",true);
        out.set("versions",frozen.payload().path("versions").deepCopy());var questions=out.putArray("issuedQuestions");
        for(var q:issued.findBySessionIdOrderByOrdinalAsc(sessionId))if(set.equals(q.getComparisonSet())) {
            var key=new BranchCatalog.Key(q.getQuestionId(),q.getQuestionVersion(),set,Objects.requireNonNullElse(q.getVariantVersion(),""));
            JsonNode snapshot=json.read(q.getSnapshotJson());
            try {if(!json.hex(snapshot).equals(json.hex(catalogs.questionSnapshot(frozen,key))))throw ExplorationApiException.integrity();}
            catch(IllegalArgumentException e){throw ExplorationApiException.integrity();}
            var row=questions.addObject();row.put("issuedId",q.getId());row.put("questionId",q.getQuestionId());row.put("questionVersion",q.getQuestionVersion());
            row.put("ordinal",q.getOrdinal());row.put("effective",q.getEffective());row.put("required",q.getRequired());row.put("text",snapshot.path("question").path("question_text").asText());
            List<JsonNode> sorted=new ArrayList<>();snapshot.path("options").forEach(sorted::add);sorted.sort(Comparator.comparingInt(o->o.path("option_order").asInt()));
            var options=row.putArray("options");
            for(JsonNode o:sorted){var option=options.addObject();option.put("code",o.path("option_code").asText());option.put("text",o.path("option_text").asText());option.put("order",o.path("option_order").asInt());}
        }
        out.put("availability",questions.isEmpty()?"NOT_ISSUED":"ISSUED");out.put("issuesNewQuestions",false);return out;
    }
    private ExplorationSession owned(long user,long id){var session=sessions.findById(id).filter(s->s.isOwnedBy(user)).orElseThrow(ExplorationComparisonService::notFound);policy.authorizeExisting(user,session.getRunMode());return session;}
    private static ExplorationApiException notFound(){return new ExplorationApiException(404,"RESOURCE_NOT_FOUND","비교할 탐색 회차 또는 결과를 찾을 수 없습니다.");}
}
