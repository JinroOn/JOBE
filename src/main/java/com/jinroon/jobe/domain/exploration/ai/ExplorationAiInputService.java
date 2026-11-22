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
    private final ExplorationEvidenceClient evidence;
    public record Prepared(JsonNode request,boolean readyForGeneration,String blockedReason){}
    public Prepared prepare(long user,long context,String purpose){var locked=contexts.readForAdapter(user,context,purpose);return new Prepared(adapter.build(locked.snapshot(),UUID.randomUUID().toString()),false,"APPROVED_EVIDENCE_NOT_BOUND");}
    public JsonNode validateResponse(JsonNode request,JsonNode response){contract.response(request,response);return response.deepCopy();}
    public JsonNode insufficientEvidence(JsonNode request){return contract.insufficient(request);}
    /** Explicit internal opt-in; rechecks owner/context after the network call. Never authorizes generation. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
    public ExplorationEvidenceClient.Outcome prepareWithEvidence(long user,long context,String purpose){
        var initial=prepare(user,context,purpose);
        var outcome=evidence.prepare(initial.request());
        var current=prepare(user,context,purpose);
        if(!initial.request().path("sourceContextHash").equals(current.request().path("sourceContextHash")))
            return new ExplorationEvidenceClient.Outcome("UNAVAILABLE","SOURCE_CONTEXT_CHANGED",false,null);
        return outcome;
    }
}
