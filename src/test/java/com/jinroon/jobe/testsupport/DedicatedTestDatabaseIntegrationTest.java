package com.jinroon.jobe.testsupport;

import static org.assertj.core.api.Assertions.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Proves spring.factories protects an ordinary legacy-style SpringBootTest with no custom annotation. */
@SpringBootTest
class DedicatedTestDatabaseIntegrationTest {
    @Autowired Environment environment;
    @Autowired DataSource source;
    @Test void ordinarySpringTestsUseTheDedicatedConnection() throws Exception {
        String expected=DedicatedTestDatabaseFactory.database(environment.getProperty("spring.datasource.url"));
        try(var connection=source.getConnection()) {assertThat(connection.getCatalog()).isEqualTo(expected);}
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(environment.getProperty("spring.datasource.hikari.jdbc-url"));
    }
}
