package com.jinroon.jobe.testsupport;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.*;

/** Test-classpath only: installed for every Spring test, including IDE launches and legacy tests. */
public final class DedicatedTestDatabaseFactory implements ContextCustomizerFactory {
    static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:13307/jobe_test?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul";

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> attributes) {
        return Guard.INSTANCE;
    }

    private enum Guard implements ContextCustomizer {
        INSTANCE;
        @Override public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
            var environment = context.getEnvironment();
            String url = environment.getProperty("jobe.test.database.url", DEFAULT_URL);
            String database = database(url); // Before refresh, Flyway, Hibernate, or any connection.
            String username = environment.getProperty("jobe.test.database.username", "root");
            String password = environment.getProperty("jobe.test.database.password", "jobe-test-only");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dedicatedTestDatabase", Map.of(
                    "spring.datasource.url", url, "spring.datasource.username", username, "spring.datasource.password", password,
                    "spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver", "spring.datasource.type", "com.zaxxer.hikari.HikariDataSource",
                    "spring.datasource.hikari.jdbc-url", url, "spring.datasource.hikari.username", username, "spring.datasource.hikari.password", password,
                    "spring.datasource.hikari.driver-class-name", "com.mysql.cj.jdbc.Driver",
                    // 캐시된 컨텍스트마다 유휴 연결을 채워 두면 테스트 DB의 연결 한도를 넘는다.
                    "spring.datasource.hikari.minimum-idle", "1")));
            context.addBeanFactoryPostProcessor(factory -> {
                if (!url.equals(environment.getProperty("spring.datasource.url"))
                        || !url.equals(environment.getProperty("spring.datasource.hikari.jdbc-url")))
                    throw unsafe("A late datasource override was detected");
                // These can execute SQL or replace URL/driver settings while opening a connection.
                for (String key : List.of("spring.datasource.hikari.connection-init-sql", "spring.datasource.jndi-name",
                        "spring.datasource.hikari.data-source-jndi", "spring.datasource.hikari.data-source-class-name",
                        "spring.flyway.url", "spring.flyway.user", "spring.flyway.password", "spring.flyway.init-sqls",
                        "spring.jpa.properties.hibernate.connection.url", "spring.jpa.properties.jakarta.persistence.jdbc.url"))
                    if (!environment.getProperty(key, "").isBlank()) throw unsafe("Datasource initialization overrides are not allowed");
                for (String key : List.of("spring.flyway.default-schema", "spring.jpa.properties.hibernate.default_catalog",
                        "spring.jpa.properties.hibernate.default_schema")) {
                    String selected = environment.getProperty(key, "");
                    if (!selected.isBlank() && !selected.equals(database)) throw unsafe("Cross-schema initialization is not allowed");
                }
                for (String selected : Binder.get(environment).bind("spring.flyway.schemas", Bindable.listOf(String.class)).orElse(List.of()))
                    if (!database.equals(selected)) throw unsafe("Cross-schema migrations are not allowed");
                if (!Binder.get(environment).bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class)).orElse(Map.of()).isEmpty())
                    throw unsafe("Additional driver properties are not allowed");
                factory.addBeanPostProcessor(new BeanPostProcessor() {
                    // Binding must finish before Hikari starts; callers cannot obtain the bean until this returns.
                    @Override public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                        if (bean instanceof DataSource source) verifyConnection(source, database);
                        return bean;
                    }
                });
            });
        }
    }

    static String database(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:mysql://")) throw unsafe("Only MySQL test connections are allowed");
            URI uri = URI.create(url.substring(5));
            if (uri.getHost() == null || !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null)
                throw unsafe("Only loopback test connections without userinfo are allowed");
            String path = uri.getRawPath();
            if (path == null || !path.matches("/jobe_test(?:_[a-z0-9]+)*") || path.length() > 65)
                throw unsafe("A dedicated test schema is required");
            Set<String> seen = new HashSet<>();
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&", -1)) {
                String[] entry = pair.split("=", 2);
                if (entry.length != 2) throw unsafe("Invalid test URL options");
                String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8), value = URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
                if (!seen.add(key)) throw unsafe("Duplicate test URL options");
                boolean valid = switch (key) {
                    case "allowPublicKeyRetrieval", "useSSL" -> Set.of("true", "false").contains(value);
                    case "serverTimezone", "connectionTimeZone" -> Set.of("Asia/Seoul", "UTC").contains(value);
                    case "characterEncoding" -> Set.of("UTF-8", "utf8").contains(value);
                    case "connectTimeout", "socketTimeout" -> value.matches("[0-9]{1,6}") && Integer.parseInt(value) > 0 && Integer.parseInt(value) <= 300000;
                    default -> false;
                };
                if (!valid) throw unsafe("Unsupported test URL options");
            }
            return path.substring(1);
        } catch (IllegalArgumentException e) { throw unsafe("Malformed test URL"); }
    }

    static void verifyConnection(DataSource source, String expected) {
        try (var connection = source.getConnection()) {
            if (!expected.equals(connection.getCatalog())) throw unsafe("The actual connected schema differs from the selected test schema");
        } catch (Exception e) {
            if (source instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception closeFailure) { e.addSuppressed(closeFailure); }
            throw new BeanInitializationException("Cannot verify the dedicated test database before schema initialization", e);
        }
    }

    private static IllegalStateException unsafe(String reason) {
        return new IllegalStateException(reason + ". Set JOBE_TEST_DATABASE_URL to a loopback MySQL jobe_test or jobe_test_* schema; "
                + "start the disposable database with docker compose -f docker-compose.test.yml up -d.");
    }
}
