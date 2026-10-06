package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.GameEntryCode;
import org.springframework.stereotype.Component;

@Component
public class SecureGameEntryCodeGenerator implements GameEntryCodeGenerator {

    @Override
    public GameEntryCode generate() {
        return GameEntryCode.newCode();
    }
}
