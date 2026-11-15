package com.jinroon.jobe.domain.exploration.support;

import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import java.util.*;

/** Stable post-result preferences only; never calls the scoring or pre-result routing engines. */
public final class PostResultQuestionPlanner {
    private static final List<String> CORE = List.of("READINESS-INFO-01","READINESS-CMP-01","READINESS-SELF-01","READINESS-ACT-02");
    private static final List<String> PLAN_EXTRA = List.of("READINESS-ACT-01","READINESS-CON-01","READINESS-INFO-02","READINESS-SELF-02");
    private static final List<String> ACTIVITY = List.of("ACTIVITY-REPORT-01","ACTIVITY-PROJECT-01","ACTIVITY-READ-01","ACTIVITY-EXPERIMENT-01","ACTIVITY-PRESENT-01","ACTIVITY-CONTENT-01");

    public List<BranchCatalog.Key> desired(BranchCatalog catalog,String purpose) {
        List<String> ids = new ArrayList<>();
        switch(purpose) {
            case "PLAN" -> { ids.addAll(CORE);ids.addAll(PLAN_EXTRA); }
            case "ACTIVITY" -> ids.addAll(ACTIVITY);
            case "BOTH" -> {ids.addAll(CORE);ids.addAll(ACTIVITY.subList(0,4));}
            default -> throw new IllegalArgumentException("Invalid support purpose");
        }
        return ids.stream().map(id->{
            var q=catalog.question(id);
            if(!q.stage().equals("POST_RESULT") || !q.role().name().equals("POST_RESULT_ONLY")
                    || !Set.of("READINESS","EXPLORATION_ACTIVITY").contains(q.module()))throw new IllegalArgumentException("Invalid support catalog");
            return catalog.key(q,"");
        }).toList();
    }
}
