package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameEntryCode;
import com.projetoxadrez.backend.game.domain.GameMode;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameCreationService {

    private final GameCreationStore store;
    private final GameEntryCodeGenerator entryCodeGenerator;
    private final AiTurnScheduler aiTurnScheduler;
    private final Clock clock;

    public GameCreationService(
            GameCreationStore store,
            GameEntryCodeGenerator entryCodeGenerator,
            AiTurnScheduler aiTurnScheduler,
            Clock clock) {
        this.store = store;
        this.entryCodeGenerator = entryCodeGenerator;
        this.aiTurnScheduler = aiTurnScheduler;
        this.clock = clock;
    }

    /**
     * Cria a partida no modo pedido (EAP 2.14.1).
     *
     * <p>Modo ausente significa humano contra humano, o único que existia antes. Partidas com IA
     * nascem ativas, já que não há segundo jogador para esperar, e por isso o trabalho da IA é
     * enfileirado aqui mesmo quando a vez já é dela.
     */
    @Transactional
    public GameSnapshot create(
            UUID creatorSessionId, GameVisibility visibility, TimeControl timeControl, GameMode mode, Side side) {
        if (timeControl == null) {
            throw new InvalidGameCreationException();
        }
        GameMode resolvedMode = mode == null ? GameMode.HUMAN_VS_HUMAN : mode;
        Instant now = clock.instant();
        GameSnapshot snapshot =
                switch (resolvedMode) {
                    case HUMAN_VS_HUMAN -> createBetweenHumans(creatorSessionId, visibility, timeControl, now);
                    case HUMAN_VS_AI -> store.create(
                            Game.createAgainstAi(
                                    timeControl, creatorSessionId, side == null ? Side.WHITE : side, now),
                            now);
                    case AI_VS_AI -> store.create(Game.createAiVersusAi(timeControl, now), now);
                };
        if (resolvedMode.hasAi()) {
            aiTurnScheduler.syncAiTurn(snapshot);
        }
        return snapshot;
    }

    private GameSnapshot createBetweenHumans(
            UUID creatorSessionId, GameVisibility visibility, TimeControl timeControl, Instant now) {
        if (visibility == null) {
            throw new InvalidGameCreationException();
        }
        GameEntryCode entryCode = visibility == GameVisibility.PRIVATE ? availableEntryCode() : null;
        return store.create(Game.create(visibility, timeControl, creatorSessionId, entryCode), now);
    }

    private GameEntryCode availableEntryCode() {
        GameEntryCode entryCode;
        do {
            entryCode = entryCodeGenerator.generate();
        } while (store.existsPrivateWaitingByEntryCode(entryCode.value()));
        return entryCode;
    }

    public static final class InvalidGameCreationException extends RuntimeException {
    }
}
