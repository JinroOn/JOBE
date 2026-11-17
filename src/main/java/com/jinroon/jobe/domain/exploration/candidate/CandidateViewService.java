package com.jinroon.jobe.domain.exploration.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.exploration.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandidateViewService {
    private final ExplorationResultRepository results;
    private final ExplorationSessionRepository sessions;
    private final ExplorationResultStore store;
    private final ExplorationRuntimeCatalog catalogs;
    private final ExplorationRuntimePolicy policy;
    private final CandidateViewProjector projector;
    private final ExplorationJson json;

    @Transactional(readOnly=true)
    public JsonNode get(long user,long resultId) {
        var result=results.findById(resultId).orElseThrow(CandidateViewService::missing);
        var session=sessions.findById(result.getSessionId()).filter(s->s.isOwnedBy(user)).orElseThrow(CandidateViewService::missing);
        policy.authorizeExisting(user,session.getRunMode());
        if(session.getStatus()!=ExplorationSessionStatus.completed)throw ExplorationApiException.integrity();
        JsonNode snapshot=store.verifiedSnapshot(result);
        if(snapshot.has("candidateView")) return projector.response(snapshot,snapshot.path("candidateView"),resultId,"COMPLETION_SNAPSHOT");
        // No catalog is needed for a verified truly empty historical result.
        if(snapshot.path("rankedCandidates").isArray() && snapshot.path("rankedCandidates").isEmpty())
            return projector.response(snapshot,projector.freeze(snapshot,json.object()),resultId,"SEALED_RESULT_PROJECTION");
        var frozen=catalogs.load(session.getId());
        if(!frozen.hash().equals(snapshot.path("bundleHash").asText())
                || !json.write(frozen.payload().path("versions")).equals(json.write(snapshot.path("versions")))
                || !json.write(frozen.payload().path("sourceHashes")).equals(json.write(snapshot.path("sourceHashes"))))throw ExplorationApiException.integrity();
        return projector.response(snapshot,projector.freeze(snapshot,frozen.payload()),resultId,"SEALED_RESULT_PROJECTION");
    }
    private static ExplorationApiException missing(){return new ExplorationApiException(404,"RESOURCE_NOT_FOUND","탐색 결과를 찾을 수 없습니다.");}
}
