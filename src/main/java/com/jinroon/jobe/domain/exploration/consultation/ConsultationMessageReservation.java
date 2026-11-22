package com.jinroon.jobe.domain.exploration.consultation;

import com.jinroon.jobe.domain.exploration.ai.OwnedGenerationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A crash cannot leave a reserved turn without its authoritative generation task. */
@Service
@RequiredArgsConstructor
public class ConsultationMessageReservation {
    private final ConsultationTurnStore turns;
    private final OwnedGenerationStore generations;
    @Transactional public ConsultationTurnStore.Draft reserve(long user,long consultation,String key,String question){
        var draft=turns.prepare(user,consultation,key,question);
        generations.reserveModel(user,draft.context(),"CONSULTATION",draft.mutation(),draft.request());
        return draft;
    }
}
