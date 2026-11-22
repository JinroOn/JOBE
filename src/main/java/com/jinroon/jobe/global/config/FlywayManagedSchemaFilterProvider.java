package com.jinroon.jobe.global.config;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Hibernate 스키마 자동 관리(ddl-auto)에서 Flyway가 관리하는 테이블을 제외한다.
 *
 * 레거시 테이블은 아직 ddl-auto=update가 관리하지만, 탐색형(exploration_*) 테이블은 Flyway SQL만 만든다.
 * 이 필터가 없으면 Flyway가 꺼졌거나(SPRING_FLYWAY_ENABLED=false) 아직 적용 전인 DB에서
 * Hibernate가 엔티티를 보고 생성 열·유일키·CHECK 제약이 빠진 테이블을 먼저 만들고,
 * 그 뒤 Flyway 마이그레이션이 "테이블이 이미 있다"며 실패한다.
 *
 * 등록: spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider
 */
public class FlywayManagedSchemaFilterProvider implements SchemaFilterProvider {

    private static final String FLYWAY_MANAGED_PREFIX = "exploration_";
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    private static final SchemaFilter FILTER = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(Table table) {
            return !isFlywayManaged(table.getName());
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    static boolean isFlywayManaged(String tableName) {
        if (tableName == null) {
            return false;
        }
        String name = tableName.replace("`", "").toLowerCase();
        return name.startsWith(FLYWAY_MANAGED_PREFIX) || name.equals(FLYWAY_HISTORY_TABLE);
    }

    @Override
    public SchemaFilter getCreateFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getValidateFilter() {
        return FILTER;
    }
}
