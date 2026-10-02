package com.projetoxadrez.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void appliesInitialMigrationToPostgresql() {
        try (ConfigurableApplicationContext context = SpringApplication.run(
                ChessBackendApplication.class,
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + postgresql.getJdbcUrl(),
                "--spring.datasource.username=" + postgresql.getUsername(),
                "--spring.datasource.password=" + postgresql.getPassword())) {
            Flyway flyway = context.getBean(Flyway.class);

            assertEquals("V1__initialize_schema.sql", flyway.info().current().getScript());
        }
    }
}
