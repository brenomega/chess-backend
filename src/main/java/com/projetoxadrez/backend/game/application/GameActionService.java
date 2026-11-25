package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Match;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameActionService {

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
        Move move = parseMove(uci);
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

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot applyAiMove(UUID gameId, Side side, long expectedRevision, String uci) {
        Match match = load(gameId);
        try {
            match.applyAiCandidate(side, expectedRevision, () -> parseMove(uci), clock.instant());
        } catch (Match.GameTimedOutException exception) {
            publish(match);
            throw exception;
        }
        return publish(match);
    }

    private static Move parseMove(String uci) {
        try {
            return Move.fromUci(uci);
        } catch (IllegalArgumentException exception) {
            throw new InvalidMoveFormatException();
        }
    }

    private GameSnapshot act(Match match, Runnable action) {
        try {
            action.run();
        } catch (Match.GameTimedOutException exception) {
            publishAndSyncAi(match);
            throw exception;
        }
        return publishAndSyncAi(match);
    }

    private GameSnapshot publishAndSyncAi(Match match) {
        GameSnapshot snapshot = publish(match);
        aiTurnScheduler.syncAiTurn(snapshot);
        return snapshot;
    }

    private Match load(UUID gameId) {
        return store.findForUpdate(gameId).orElseThrow(GameNotFoundException::new);
    }

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
