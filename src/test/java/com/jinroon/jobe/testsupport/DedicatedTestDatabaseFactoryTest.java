package com.jinroon.jobe.testsupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class DedicatedTestDatabaseFactoryTest {
    @ParameterizedTest @ValueSource(strings={
        "jdbc:mysql://127.0.0.1/jobe", "jdbc:mysql://localhost/production", "jdbc:mysql://localhost/not_jobe_test",
        "jdbc:mysql://localhost/jobe_test_backup-production", "jdbc:mysql://localhost/jobe_test/other", "jdbc:mysql://localhost/%6aobe_test",
        "jdbc:mysql://db.example.invalid/jobe_test", "jdbc:mysql://localhost,db.example.invalid/jobe_test",
        "jdbc:mysql:loadbalance://localhost/jobe_test", "jdbc:h2:mem:jobe_test", "jdbc:mysql://user:secret@localhost/jobe_test",
        "jdbc:mysql://localhost/jobe_test#other", "jdbc:mysql://localhost/jobe_test?createDatabaseIfNotExist=true",
        "jdbc:mysql://localhost/jobe_test?sessionVariables=anything", "jdbc:mysql://localhost/jobe_test?allowMultiQueries=true",
        "jdbc:mysql://localhost/jobe_test?useSSL=false&useSSL=true", "jdbc:mysql://localhost/jobe_test?socketTimeout=0"
    })
    void rejectsDevelopmentRemoteAmbiguousAndDriverOverrideUrls(String url) {
        assertThatThrownBy(()->DedicatedTestDatabaseFactory.database(url)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JOBE_TEST_DATABASE_URL").hasMessageNotContaining("secret");
    }

    @ParameterizedTest @ValueSource(strings={"jdbc:mysql://localhost:13307/jobe_test", "jdbc:mysql://127.0.0.1:13422/jobe_test_20261116", "jdbc:mysql://[::1]:13307/jobe_test"})
    void allowsExplicitLocalTestSchemas(String url) { assertThat(DedicatedTestDatabaseFactory.database(url)).startsWith("jobe_test"); }

    @Test void ordinaryDevelopmentEnvironmentIsReplacedByIndependentTestSettings() {
        try(var context=context(Map.of("spring.datasource.url","jdbc:mysql://localhost:3307/jobe",
                "spring.datasource.password","production-secret", "jobe.test.database.url",DedicatedTestDatabaseFactory.DEFAULT_URL))) {
            customize(context);
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(DedicatedTestDatabaseFactory.DEFAULT_URL);
            assertThat(context.getEnvironment().getProperty("spring.datasource.password")).isNotEqualTo("production-secret");
        }
    }

    @Test void explicitlyUnsafeTestUrlFailsBeforeAnyDatasourceOrSchemaInitializerExists() {
        AtomicBoolean created=new AtomicBoolean();
        try(var context=context(Map.of("jobe.test.database.url","jdbc:mysql://localhost/jobe"))) {
            context.registerBean("schemaInitializer",Object.class,()->{created.set(true);return new Object();});
            assertThatThrownBy(()->{customize(context);context.refresh();}).isInstanceOf(IllegalStateException.class);
            assertThat(created).isFalse();
        }
    }

    @Test void lateOverridesFailBeforeBeanCreation() {
        AtomicBoolean created=new AtomicBoolean();
        try(var context=context(Map.of())) {
            customize(context);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("late",Map.of("spring.datasource.url","jdbc:mysql://localhost/jobe")));
            context.registerBean("schemaInitializer",Object.class,()->{created.set(true);return new Object();});
            assertThatThrownBy(context::refresh).hasMessageContaining("late datasource override");
            assertThat(created).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings={"spring.datasource.hikari.connection-init-sql", "spring.datasource.jndi-name",
            "spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari.data-source-properties.sessionVariables",
            "spring.flyway.url", "spring.flyway.default-schema", "spring.flyway.schemas", "spring.flyway.init-sqls",
            "spring.jpa.properties.hibernate.default_catalog", "spring.jpa.properties.hibernate.connection.url"})
    void initializationAndDriverOverridesFailBeforeCreatingADatasource(String key) {
        AtomicBoolean created=new AtomicBoolean();
        try(var context=context(Map.of(key,"synthetic-unsafe"))) {
            customize(context);
            context.registerBean(DataSource.class,()->{created.set(true);return mock(DataSource.class);});
            assertThatThrownBy(context::refresh).isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed");
            assertThat(created).isFalse();
        }
    }

    @Test void actualConnectedCatalogIsCheckedBeforeAnyApplicationSql() throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getCatalog()).thenReturn("jobe");
        assertThatThrownBy(()->DedicatedTestDatabaseFactory.verifyConnection(source,"jobe_test"))
                .hasMessageContaining("before schema initialization");
        verify(connection).getCatalog();verify(connection).close();verifyNoMoreInteractions(connection);
    }

    @Test void validConnectionIsClosedWithoutExecutingInitializationSql() throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getCatalog()).thenReturn("jobe_test");
        DedicatedTestDatabaseFactory.verifyConnection(source,"jobe_test");
        verify(connection).getCatalog();verify(connection).close();verifyNoMoreInteractions(connection);
    }

    @Test void aWrongActualCatalogStopsDependentSchemaInitializers() throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getCatalog()).thenReturn("jobe");
        AtomicBoolean migrated=new AtomicBoolean();
        try(var context=context(Map.of())) {
            customize(context);context.registerBean(DataSource.class,()->source);
            context.registerBean("migration",Object.class,()->{context.getBean(DataSource.class);migrated.set(true);return new Object();});
            assertThatThrownBy(context::refresh).hasMessageContaining("before schema initialization");
            assertThat(migrated).isFalse();
        }
        verify(connection).getCatalog();verify(connection).close();verifyNoMoreInteractions(connection);
    }

    private AnnotationConfigApplicationContext context(Map<String,Object> properties) {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",properties));
        return context;
    }
    private void customize(AnnotationConfigApplicationContext context) {
        new DedicatedTestDatabaseFactory().createContextCustomizer(getClass(),List.of()).customizeContext(context,null);
    }
}
