package com.projetoxadrez.backend.session.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GuestSessionProperties.class)
public class GuestSessionConfiguration {

    @Bean
    Clock guestSessionClock() {
        return Clock.systemUTC();
    }
}
