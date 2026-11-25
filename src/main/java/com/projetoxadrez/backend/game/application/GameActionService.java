package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Match;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameActionService {

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
    public GameSnapshot move(Request request, String uci) {
        Move move = parseMove(uci);
        return act(request, "game.move", move.toUci(),
                (match, revision) -> match.applyMove(request.sessionId(), revision, move, clock.instant()));
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot abandon(Request request) {
        return act(request, "game.abandon", "",
                (match, revision) -> match.abandon(request.sessionId(), revision, clock.instant()));
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot offerDraw(Request request) {
        return act(request, "draw.offer", "",
                (match, revision) -> match.offerDraw(request.sessionId(), revision, clock.instant()));
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public GameSnapshot respondDraw(Request request, boolean accept) {
        return act(request, "draw.respond", Boolean.toString(accept),
                (match, revision) -> match.respondDraw(request.sessionId(), revision, accept, clock.instant()));
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

    private GameSnapshot act(Request request, String type, String payload, BiConsumer<Match, Long> action) {
        UUID gameId = request.gameId();
        UUID sessionId = request.sessionId();
        Match match = load(gameId);
        match.sideOf(sessionId).orElseThrow(Match.NotAParticipantException::new);

        String identity = type + ":" + Objects.toString(request.revision(), "") + ":" + payload;
        Optional<String> previous = store.findActionIdentity(gameId, sessionId, request.requestId());
        if (previous.isPresent()) {
            if (!previous.orElseThrow().equals(identity)) {
                throw new RequestIdConflictException();
            }
            return store.snapshot(gameId);
        }

        Long expectedRevision = request.revision();
        if (expectedRevision == null) {
            throw new MissingRevisionException();
        }
        long previousRevision = match.revision();
        try {
            action.accept(match, expectedRevision);
        } catch (Match.GameTimedOutException exception) {
            saveAndPublish(match, null);
            throw exception;
        }
        store.recordAction(gameId, sessionId, request.requestId(), identity);
        return match.revision() == previousRevision
                ? store.snapshot(gameId).withClock(
                        new GameSnapshot.Clock(match.whiteRemainingMs(), match.blackRemainingMs(), match.activeSide()))
                : saveAndPublish(match, request.connectionId());
    }

    private Match load(UUID gameId) {
        return store.findForUpdate(gameId).orElseThrow(GameNotFoundException::new);
    }

    private GameSnapshot publish(Match match) {
        GameSnapshot snapshot = store.save(match);
        statePublisher.publishAfterCommit(snapshot);
        return snapshot;
    }

    private GameSnapshot saveAndPublish(Match match, String connectionId) {
        GameSnapshot snapshot = store.save(match);
        aiTurnScheduler.syncAiTurn(snapshot);
        statePublisher.publishAfterCommit(snapshot, connectionId);
        return snapshot;
    }

    public record Request(
            UUID gameId, UUID sessionId, String requestId, String connectionId, Long revision) {
    }

    public static final class MissingRevisionException extends RuntimeException {
    }

    public static final class RequestIdConflictException extends RuntimeException {
    }

    public static final class GameNotFoundException extends RuntimeException {
    }

    public static final class InvalidMoveFormatException extends RuntimeException {
    }
}
