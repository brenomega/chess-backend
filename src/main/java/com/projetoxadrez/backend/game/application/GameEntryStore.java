package com.projetoxadrez.backend.game.application;

import java.time.Instant;
import java.util.UUID;

public interface GameEntryStore {

    GameSnapshot join(UUID gameId, UUID sessionId, String entryCode, Instant now);
}
