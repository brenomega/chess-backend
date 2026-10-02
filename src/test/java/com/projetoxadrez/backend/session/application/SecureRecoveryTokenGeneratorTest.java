package com.projetoxadrez.backend.session.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecureRecoveryTokenGeneratorTest {

    @Test
    void generatesUrlSafeTokensWith256BitsOfEntropy() {
        String token = new SecureRecoveryTokenGenerator().generate();

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }
}
