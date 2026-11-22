package com.jinroon.jobe.domain.exploration.consultation;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.ai.OwnedMockExecutionService;
import com.jinroon.jobe.domain.exploration.ai.ExplorationJobService;
import com.jinroon.jobe.domain.exploration.ai.ExplorationJobClient;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class ConsultationMessageService {
    private final ConsultationTurnStore turns;
    private final ConsultationMessageReservation reservations;
    private final OwnedMockExecutionService execution;
    private final ExplorationJobService jobs;
    @Transactional(propagation=Propagation.NEVER)
    public void cancel(long user,long consultation,String retryKey){
        var draft=turns.cancellation(user,consultation,retryKey);
        try{jobs.command(user,draft.context(),"CONSULTATION",draft.mutation(),"cancel");}
        catch(ExplorationJobClient.Unavailable failure){if(!turns.cancelled(user,draft))throw failure;}
        if(!turns.cancelled(user,draft))throw new ExplorationApiException(409,"CONSULTATION_CANCEL_CONFLICT","상담 요청 취소 상태를 확인해 주세요.");
    }
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode send(long user,long consultation,String key,String question){
        var replay=turns.replay(user,consultation,key,question);if(replay!=null)return replay;
        var guidance=turns.trySuggestedReply(user,consultation,key,question);if(guidance!=null)return guidance;
        execution.requireModelEnabled();
        var draft=reservations.reserve(user,consultation,key,question);
        replay=turns.replay(user,consultation,key,question);if(replay!=null)return replay;
        try{execution.executeConversation(user,draft.context(),draft.mutation(),draft.request());}
        catch(RuntimeException failure){
            // Cleanup is best effort only. It never changes completed/live jobs or hides the original failure.
            try{turns.releaseTerminal(user,draft);}catch(RuntimeException ignored){}
            throw failure;
        }
        var answer=turns.replay(user,consultation,key,question);
        if(answer==null)throw new ExplorationApiException(503,"CONSULTATION_GENERATION_UNAVAILABLE","상담 응답을 확인할 수 없습니다.");
        return answer;
    }
}
