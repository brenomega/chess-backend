package com.projetoxadrez.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void appliesTheRequiredSchemaAndPrivateEntryCodeInvariant() {
        try (ConfigurableApplicationContext context = SpringApplication.run(
                ChessBackendApplication.class,
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + postgresql.getJdbcUrl(),
                "--spring.datasource.username=" + postgresql.getUsername(),
                "--spring.datasource.password=" + postgresql.getPassword())) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

            assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('chess.game')", String.class))
                    .isEqualTo("chess.game");
            Integer matchingIndexes = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_index i
                    JOIN pg_class index_class ON index_class.oid = i.indexrelid
                    JOIN pg_class table_class ON table_class.oid = i.indrelid
                    JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                    WHERE namespace.nspname = 'chess'
                      AND table_class.relname = 'game'
                      AND index_class.relname = 'game_private_waiting_entry_code_uq'
                      AND i.indisunique
                      AND pg_get_indexdef(i.indexrelid) LIKE '%entry_code%'
                      AND pg_get_expr(i.indpred, i.indrelid) LIKE '%PRIVATE%'
                      AND pg_get_expr(i.indpred, i.indrelid) LIKE '%WAITING%'
                    """, Integer.class);
            assertThat(matchingIndexes).isOne();
        }
    }
}
