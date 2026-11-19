package com.jinroon.jobe.domain.exploration.ai;

import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExplorationModelAvailability {
    private final ExplorationJobClient client;

    @Value("${exploration.enabled:false}") private boolean explorationEnabled;
    @Value("${exploration.runtime.run-mode:PUBLIC}") private String runMode;
    @Value("${exploration.jobs.erasure.enabled:false}") private boolean erasureEnabled;
    @Value("${exploration.jobs.model-enabled:false}") private boolean modelEnabled;
    @Value("${exploration.evidence.enabled:false}") private boolean evidenceEnabled;

    boolean devJobsEnabled() {
        return explorationEnabled && erasureEnabled && "DEV".equals(runMode);
    }

    boolean modelExecutionEnabled() {
        return modelEnabled && devJobsEnabled();
    }

    /** Screen entry uses local settings only. Generation validates live evidence and service health. */
    public boolean availableFor(ExplorationRunMode sessionMode) {
        return sessionMode == ExplorationRunMode.DEV && modelExecutionEnabled()
                && evidenceEnabled && client.readyForModel();
    }
}
