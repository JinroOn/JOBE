package com.jinroon.jobe.domain.exploration.plan;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class ExplorationPlanService {
    private final ExplorationPlanStore plans;
    private final ExplorationPlanReservation reservations;
    private final OwnedMockExecutionService execution;
    private final ExplorationJobService jobs;
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode create(long user,long result,String key,String major,Long revision){
        var draft=plans.find(user,result,key,major,revision);
        if(draft==null){execution.requireModelEnabled();draft=reservations.reserve(user,result,key,major,revision);}
        return attempt(user,draft);
    }
    @Transactional(propagation=Propagation.NEVER)
    public JsonNode retry(long user,long id){return attempt(user,plans.owned(user,id));}
    private JsonNode attempt(long user,ExplorationPlanStore.Draft draft){
        if(plans.canExecute(user,draft.id())){
            execution.requireModelEnabled();
            try{execution.executePlan(user,draft.context(),draft.mutation(),draft.request());}
            catch(ExplorationJobClient.Unavailable e){return plans.get(user,draft.id());}
            catch(ExplorationAiContract.Invalid e){return plans.get(user,draft.id());}
            catch(ExplorationApiException e){if(!e.getCode().equals("GENERATION_TASK_CONFLICT"))throw e;}
        }
        return plans.get(user,draft.id());
    }
    @Transactional(propagation=Propagation.NEVER)
    public void cancel(long user,long id){
        var draft=plans.owned(user,id);
        try{jobs.command(user,draft.context(),"PLAN",draft.mutation(),"cancel");}
        catch(ExplorationJobClient.Unavailable e){if(!plans.get(user,id).path("failureCode").asText().equals("CANCELLED"))throw e;}
    }
}
