package com.jinroon.jobe.domain.exploration.consultation;

import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import com.jinroon.jobe.domain.user.service.UserWriteGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens an owned, frozen result conversation; no provider, generated welcome or legacy write. */
@Service
@RequiredArgsConstructor
public class ExplorationConsultationService {
    private final UserWriteGuard users;
    private final ResultContextService contexts;
    private final PostResultSupportService support;
    private final JdbcTemplate jdbc;
    private final ConsultationTurnStore turns;

    @Transactional
    public ConsultationDtos.Session open(long user,long result,String key){
        if(key==null||!key.matches("[!-~]{1,128}"))throw new ExplorationApiException(400,"INVALID_REQUEST","멱등 키 형식을 확인해 주세요.");
        // Serializes open with support mutations, account deletion, and another open by this owner.
        users.requireActive(user);
        var state=support.get(user,result); // ownership, runtime policy and immutable result verification
        var existing=jdbc.queryForList("SELECT id,context_id FROM exploration_consultation WHERE result_id=?",result);
        long context;
        if(existing.isEmpty()){
            context = contexts.prepare(user, result, "CONSULTATION_OPEN", "CONSULTATION",
                    state.path("supportRevision").asLong(), null).body().path("contextId").asLong();
            jdbc.update("INSERT INTO exploration_consultation(result_id,context_id) VALUES (?,?)",result,context);
        }else context=((Number)existing.get(0).get("context_id")).longValue();
        contexts.lockForUse(user,context,"CONSULTATION");
        var row=jdbc.queryForMap("SELECT id FROM exploration_consultation WHERE result_id=? FOR UPDATE",result);
        long consultation=((Number)row.get("id")).longValue();
        var messages=turns.history(user,consultation).stream().map(ConsultationDtos::message).toList();
        return new ConsultationDtos.Session(consultation,result,messages,turns.pending(user,consultation));
    }
}
