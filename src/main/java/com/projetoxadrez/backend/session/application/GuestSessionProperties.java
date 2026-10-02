package com.projetoxadrez.backend.session.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("guest.session")
public class GuestSessionProperties {

    private Duration ttl = Duration.ofDays(30);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("guest.session.ttl must be positive");
        }
        this.ttl = ttl;
    }
}
