package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ExplorationRuntimePolicyTest {
    @Test void developmentRequiresDedicatedProfileAndExplicitUser() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("exploration-dev");
        var policy = new ExplorationRuntimePolicy(environment, "DEV", "1, 2");
        assertThat(policy.authorize(1L)).isEqualTo(ExplorationRunMode.DEV);
        assertThatThrownBy(() -> policy.authorize(3L)).isInstanceOf(ExplorationApiException.class);
    }
    @Test void onlyExactWildcardOpensDevelopmentToEverySignedInUser() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("exploration-dev");
        assertThat(new ExplorationRuntimePolicy(environment, "DEV", " * ").authorize(8_200_099_999L)).isEqualTo(ExplorationRunMode.DEV);
        for (String users : new String[]{"", "*,1", "1,*", "**"})
            assertThatThrownBy(() -> new ExplorationRuntimePolicy(environment, "DEV", users).authorize(3L)).isInstanceOf(ExplorationApiException.class);
    }
    @Test void wildcardKeepsProfileAndModeGates() {
        MockEnvironment withoutProfile = new MockEnvironment(); withoutProfile.setActiveProfiles("docker");
        assertThatThrownBy(() -> new ExplorationRuntimePolicy(withoutProfile, "DEV", "*").authorize(1L)).isInstanceOf(ExplorationApiException.class);
        MockEnvironment production = new MockEnvironment(); production.setActiveProfiles("exploration-dev", "prod");
        assertThatThrownBy(() -> new ExplorationRuntimePolicy(production, "DEV", "*").authorize(1L)).isInstanceOf(ExplorationApiException.class);
        MockEnvironment development = new MockEnvironment(); development.setActiveProfiles("exploration-dev");
        for (String mode : new String[]{"PUBLIC", "PILOT"})
            assertThatThrownBy(() -> new ExplorationRuntimePolicy(development, mode, "*").authorize(1L)).isInstanceOf(ExplorationApiException.class);
    }
    @Test void genericDevProfileDoesNotEnableReviewedContent() {
        MockEnvironment environment = new MockEnvironment(); environment.setActiveProfiles("dev");
        assertThatThrownBy(() -> new ExplorationRuntimePolicy(environment, "DEV", "1").authorize(1L)).isInstanceOf(ExplorationApiException.class);
    }
    @Test void productionProfileCannotEnableDevelopmentMode() {
        for (String profile : new String[]{"prod", "production", "PROD"}) {
            MockEnvironment environment = new MockEnvironment(); environment.setActiveProfiles("exploration-dev", profile);
            assertThatThrownBy(() -> new ExplorationRuntimePolicy(environment, "DEV", "1").authorize(1L)).isInstanceOf(ExplorationApiException.class);
        }
    }
    @Test void publicAndPilotRemainClosedWithoutImplementedReviewGates() {
        MockEnvironment environment = new MockEnvironment(); environment.setActiveProfiles("exploration-dev");
        for (String mode : new String[]{"PUBLIC", "PILOT", "INVALID"})
            assertThatThrownBy(() -> new ExplorationRuntimePolicy(environment, mode, "1").authorize(1L)).isInstanceOf(ExplorationApiException.class);
    }
}
