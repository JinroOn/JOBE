package com.jinroon.jobe.domain.exploration.service;

import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Server-owned mode. PUBLIC/PILOT remain closed until a real approval workflow is implemented. */
@Component
public class ExplorationRuntimePolicy {
    /** dev-user-ids를 이 값 하나로만 설정하면 로그인한 사용자 모두가 DEV 회차를 쓸 수 있다. */
    private static final String ALL_MEMBERS = "*";

    private final Environment environment;
    private final String mode;
    private final Set<String> devUsers;

    public ExplorationRuntimePolicy(Environment environment,
            @Value("${exploration.runtime.run-mode:PUBLIC}") String mode,
            @Value("${exploration.runtime.dev-user-ids:}") String devUsers) {
        this.environment = environment;
        this.mode = mode;
        this.devUsers = Arrays.stream(devUsers.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
    }

    public ExplorationRunMode authorize(Long userId) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if ("DEV".equals(mode) && profiles.contains("exploration-dev")
                && profiles.stream().noneMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"))
                && (devUsers.equals(Set.of(ALL_MEMBERS)) || devUsers.contains(userId.toString()))) return ExplorationRunMode.DEV;
        throw new ExplorationApiException(503, "NO_ACTIVE_CATALOG", "승인된 실행 범위의 탐색 자료가 없습니다.");
    }

    public void authorizeExisting(Long userId, ExplorationRunMode pinnedMode) {
        if (authorize(userId) != pinnedMode) throw new ExplorationApiException(503, "NO_ACTIVE_CATALOG", "회차 실행 범위를 확인할 수 없습니다.");
    }
}
