package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Hibernate ddl-auto가 Flyway 관리 테이블을 건드리지 않도록 하는 필터 규칙.
 * 실제 효과(Flyway를 끄고 띄워도 exploration_* 테이블이 생기지 않음)는 임시 MySQL로 별도 확인했다.
 */
class FlywayManagedSchemaFilterProviderTest {

    @Test
    void explorationTablesAndFlywayHistoryAreExcluded() {
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("exploration_session")).isTrue();
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("`exploration_answer_revision`")).isTrue();
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("EXPLORATION_RESULT")).isTrue();
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("flyway_schema_history")).isTrue();
    }

    @Test
    void legacyTablesStayManagedByHibernate() {
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("users")).isFalse();
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("diagnosis_sessions")).isFalse();
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged("user_favorites")).isFalse();
        assertThat(FlywayManagedSchemaFilterProvider.isFlywayManaged(null)).isFalse();
    }

    @Test
    void allSchemaActionsUseTheSameFilter() {
        FlywayManagedSchemaFilterProvider provider = new FlywayManagedSchemaFilterProvider();

        assertThat(provider.getCreateFilter())
                .isSameAs(provider.getMigrateFilter())
                .isSameAs(provider.getDropFilter())
                .isSameAs(provider.getValidateFilter())
                .isSameAs(provider.getTruncatorFilter());
    }
}
