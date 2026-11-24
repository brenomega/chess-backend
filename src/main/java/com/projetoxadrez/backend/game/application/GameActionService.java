package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.domain.Match;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ações executadas durante a partida: lance, abandono e empate (EAP 2.9).
 *
 * <p>Cada ação roda numa transação: a partida é carregada com trava, o agregado {@link Match}
 * decide, e o estado resultante é publicado aos participantes conectados. Quando a ação é
 * rejeitada, a transação não grava nada — a partida fica exatamente como estava.
 */
@Service
public class GameActionService {

    /** Marca "não verificar revisão", para clientes que não a enviam. */
    public static final long ANY_REVISION = -1;

    private final GameActionStore store;
    private final GameStatePublisher statePublisher;
    private final Clock clock;

    public GameActionService(GameActionStore store, GameStatePublisher statePublisher, Clock clock) {
        this.store = store;
        this.statePublisher = statePublisher;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot move(UUID gameId, UUID sessionId, long expectedRevision, String uci) {
        Match match = load(gameId);
        Move move;
        try {
            move = Move.fromUci(uci);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMoveFormatException();
        }
        return act(match, () -> match.applyMove(sessionId, expectedRevision, move, clock.instant()));
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot abandon(UUID gameId, UUID sessionId) {
        Match match = load(gameId);
        return act(match, () -> match.abandon(sessionId, clock.instant()));
    }

    /**
     * Executa a ação e grava. Se o relógio encerrou a partida, o fim é gravado e publicado mesmo
     * com a ação rejeitada: o timeout é um fato novo, não um efeito da ação (EAP 2.10.2).
     */
    private GameSnapshot act(Match match, Runnable action) {
        try {
            action.run();
        } catch (Match.GameTimedOutException exception) {
            publish(match);
            throw exception;
        }
        return publish(match);
    }

    private Match load(UUID gameId) {
        return store.findForUpdate(gameId).orElseThrow(GameNotFoundException::new);
    }

    /** Grava o estado e publica o snapshot aos inscritos, depois do commit (EAP 2.8.3). */
    private GameSnapshot publish(Match match) {
        GameSnapshot snapshot = store.save(match);
        statePublisher.publishAfterCommit(snapshot);
        return snapshot;
    }

    public static final class GameNotFoundException extends RuntimeException {
    }

    public static final class InvalidMoveFormatException extends RuntimeException {
    }
}
