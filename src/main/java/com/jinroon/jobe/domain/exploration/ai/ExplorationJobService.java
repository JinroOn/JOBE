package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Internal orchestration only; caller must obtain user from authenticated principal. */
@Service
@RequiredArgsConstructor
public class ExplorationJobService {
    private final ExplorationJobOwnerService owners;
    private final ResultContextService contexts;
    private final ExplorationAiInputAdapter adapter;
    private final ExplorationJobClient client;
    private final OwnedGenerationStore generations;
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode command(long user,long context,String purpose,String mutation,String operation) {
        if(mutation==null || !mutation.matches("[A-Za-z0-9_-]{1,80}"))throw new ExplorationJobClient.Unavailable();
        var snapshot=contexts.readForAdapter(user,context,purpose).snapshot();
        String owner=owners.ownedKey(user);
        String requestId=UUID.nameUUIDFromBytes((owner+":"+mutation).getBytes(StandardCharsets.UTF_8)).toString();
        JsonNode request=adapter.build(snapshot,requestId);
        if(operation.equals("enqueue") || operation.equals("cancel"))generations.reserve(user,context,purpose,mutation,request);
        if(operation.equals("cancel"))generations.cancel(user,context,purpose,mutation);
        if(operation.equals("read")){var status=generations.status(user,context,purpose,mutation,request);if(status!=null)return status;}
        JsonNode result=client.command(operation,owner,mutation,request);
        JsonNode current=adapter.build(contexts.readForAdapter(user,context,purpose).snapshot(),requestId);
        if(!request.equals(current))throw new ExplorationJobClient.Unavailable();
        if(!result.path("status").asText().equals("INSUFFICIENT_EVIDENCE")){var status=generations.status(user,context,purpose,mutation,request);if(status!=null)return status;}
        return result;
    }
}
