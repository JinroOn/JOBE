package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Internal bridge; no public preview/generation endpoint and no legacy AI client fallback. */
@Service
@RequiredArgsConstructor
public class ExplorationAiInputService {
    private final ResultContextService contexts;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationAiContract contract;
    public record Prepared(JsonNode request,boolean readyForGeneration,String blockedReason){}
    public Prepared prepare(long user,long context,String purpose){var locked=contexts.readForAdapter(user,context,purpose);return new Prepared(adapter.build(locked.snapshot(),UUID.randomUUID().toString()),false,"APPROVED_EVIDENCE_NOT_BOUND");}
    public JsonNode validateResponse(JsonNode request,JsonNode response){contract.response(request,response);return response.deepCopy();}
    public JsonNode insufficientEvidence(JsonNode request){return contract.insufficient(request);}
}
