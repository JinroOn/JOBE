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
        JsonNode stored=generations.requestForOperation(user,context,purpose,mutation);
        JsonNode request=stored==null?adapter.build(snapshot,requestId):stored;
        boolean conversation=ExplorationAiContract.CONVERSATION_VERSION.equals(request.path("contractVersion").asText())||ExplorationAiContract.PLAN12_VERSION.equals(request.path("contractVersion").asText());
        if(conversation&&operation.equals("enqueue"))throw new ExplorationJobClient.Conflict();
        if(operation.equals("enqueue"))generations.reserve(user,context,purpose,mutation,request);
        if(operation.equals("cancel"))generations.reserveForCancel(user,context,purpose,mutation,request);
        if(operation.equals("cancel"))generations.cancel(user,context,purpose,mutation);
        if(operation.equals("read")){var status=generations.status(user,context,purpose,mutation,request);if(status!=null)return status;}
        JsonNode result=client.command(operation,owner,mutation,request);
        JsonNode current=conversation?generations.requestForOperation(user,context,purpose,mutation):adapter.build(contexts.readForAdapter(user,context,purpose).snapshot(),requestId);
        if(!request.equals(current))throw new ExplorationJobClient.Unavailable();
        if(!result.path("status").asText().equals("INSUFFICIENT_EVIDENCE")){var status=generations.status(user,context,purpose,mutation,request);if(status!=null)return status;}
        return result;
    }
}
