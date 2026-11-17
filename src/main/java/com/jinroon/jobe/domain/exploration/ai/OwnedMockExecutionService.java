package com.jinroon.jobe.domain.exploration.ai;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Explicit MOCK-only execution. Java owns the last account/context/cancel/lease decision. */
@Service
@RequiredArgsConstructor
public class OwnedMockExecutionService {
    private final ExplorationJobService jobs;
    private final ExplorationJobOwnerService owners;
    private final OwnedGenerationStore store;
    private final ExplorationJobClient client;
    @Value("${exploration.jobs.mock-enabled:false}") private boolean enabled;
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode execute(long user,long context,String purpose,String mutation){
        if(!enabled)throw new ExplorationJobClient.Unavailable();
        JsonNode queued=jobs.command(user,context,purpose,mutation,"enqueue");
        if(queued.path("status").asText().equals("INSUFFICIENT_EVIDENCE"))return queued;
        var existing=store.completed(user,context,purpose,mutation);if(existing!=null)return existing;
        var lease=store.claim(user,context,purpose,mutation);
        String owner=owners.ownedKey(user);
        var result=client.executeMock(owner,mutation,lease.request());
        return store.completeMock(user,context,purpose,mutation,lease.token(),result);
    }
}
