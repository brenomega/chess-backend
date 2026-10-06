package com.projetoxadrez.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class ChessBackendApplicationTests {

    @Test
    void applicationStarts() {
        try (ConfigurableApplicationContext context = SpringApplication.run(
                ChessBackendApplication.class,
                "--spring.main.web-application-type=none",
                "--spring.main.lazy-initialization=true",
                "--spring.flyway.enabled=false",
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")) {
        }
    }
}
