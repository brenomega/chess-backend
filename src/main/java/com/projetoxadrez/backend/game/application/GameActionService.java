package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.chess.Side;
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
    private final AiTurnScheduler aiTurnScheduler;
    private final Clock clock;

    public GameActionService(
            GameActionStore store,
            GameStatePublisher statePublisher,
            AiTurnScheduler aiTurnScheduler,
            Clock clock) {
        this.store = store;
        this.statePublisher = statePublisher;
        this.aiTurnScheduler = aiTurnScheduler;
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

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot offerDraw(UUID gameId, UUID sessionId, long expectedRevision) {
        Match match = load(gameId);
        return act(match, () -> match.offerDraw(sessionId, expectedRevision, clock.instant()));
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot respondDraw(UUID gameId, UUID sessionId, long expectedRevision, boolean accept) {
        Match match = load(gameId);
        return act(match, () -> match.respondDraw(sessionId, expectedRevision, accept, clock.instant()));
    }

    /**
     * Aplica o lance devolvido pelo worker de IA (EAP 2.14.3).
     *
     * <p>Ao contrário das ações humanas, não reenfileira o turno seguinte: quem fecha o trabalho e
     * decide o próximo é {@code AiJobService}, para não cancelar o trabalho que está sendo
     * concluído neste mesmo instante.
     */
    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot applyAiMove(UUID gameId, Side side, long expectedRevision, String uci) {
        Match match = load(gameId);
        Move move;
        try {
            move = Move.fromUci(uci);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMoveFormatException();
        }
        try {
            match.applyAiMove(side, expectedRevision, move, clock.instant());
        } catch (Match.GameTimedOutException exception) {
            publish(match);
            throw exception;
        }
        return publish(match);
    }

    /**
     * Executa a ação e grava. Se o relógio encerrou a partida, o fim é gravado e publicado mesmo
     * com a ação rejeitada: o timeout é um fato novo, não um efeito da ação (EAP 2.10.2).
     */
    private GameSnapshot act(Match match, Runnable action) {
        try {
            action.run();
        } catch (Match.GameTimedOutException exception) {
            publishAndSyncAi(match);
            throw exception;
        }
        return publishAndSyncAi(match);
    }

    /** Grava, publica e acerta a fila da IA: a ação humana pode ter passado a vez para ela. */
    private GameSnapshot publishAndSyncAi(Match match) {
        GameSnapshot snapshot = publish(match);
        aiTurnScheduler.syncAiTurn(snapshot);
        return snapshot;
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
