package com.jinroon.jobe.domain.exploration.plan;
import com.jinroon.jobe.domain.exploration.ai.OwnedGenerationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExplorationPlanReservation {
    private final ExplorationPlanStore plans;
    private final OwnedGenerationStore generations;
    @Transactional public ExplorationPlanStore.Draft reserve(long user,long result,String key,String major,Long revision){
        var draft=plans.prepare(user,result,key,major,revision);
        generations.reserveModel(user,draft.context(),"PLAN",draft.mutation(),draft.request());return draft;
    }
}
