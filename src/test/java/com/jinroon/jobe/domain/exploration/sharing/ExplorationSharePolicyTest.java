package com.jinroon.jobe.domain.exploration.sharing;

import static org.assertj.core.api.Assertions.*;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ExplorationSharePolicyTest {
    private ExplorationSharePolicy policy(boolean enabled,String mode,String... profiles){var env=new MockEnvironment();env.setActiveProfiles(profiles);return new ExplorationSharePolicy(new ExplorationRuntimePolicy(env,mode,"1"),enabled);}
    @Test void dedicatedDevOwnerAndExplicitSharingFlagAreRequired(){assertThat(policy(true,"DEV","exploration-dev").available(1L,ExplorationRunMode.DEV)).isTrue();assertThat(policy(false,"DEV","exploration-dev").available(1L,ExplorationRunMode.DEV)).isFalse();assertThat(policy(true,"DEV","exploration-dev").available(2L,ExplorationRunMode.DEV)).isFalse();}
    @Test void publicPilotAndProductionRemainClosedEvenWithFlagEnabled(){for(String mode:new String[]{"PUBLIC","PILOT"})assertThat(policy(true,mode,"exploration-dev").available(1L,ExplorationRunMode.valueOf(mode))).isFalse();for(String profile:new String[]{"prod","production","PROD"})assertThat(policy(true,"DEV","exploration-dev",profile).available(1L,ExplorationRunMode.DEV)).isFalse();}
    @Test void genericDevProfileAndRemotePeersCannotEnableAnonymousRead(){assertThat(policy(true,"DEV","dev").available(1L,ExplorationRunMode.DEV)).isFalse();var policy=policy(true,"DEV","exploration-dev");for(String remote:new String[]{"203.0.113.1","10.0.0.1","localhost","127.0.0.2","::ffff:127.0.0.1"})assertThat(policy.localReader(remote)).isFalse();assertThat(policy.localReader("127.0.0.1")).isTrue();assertThat(policy.localReader("::1")).isTrue();}
}
