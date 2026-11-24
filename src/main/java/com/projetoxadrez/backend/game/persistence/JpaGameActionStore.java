package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameActionStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.chess.MoveHistory;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameId;
import com.projetoxadrez.backend.game.domain.Match;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaGameActionStore implements GameActionStore {

    private final GameRepository repository;
    private final GameSnapshotFactory snapshotFactory;

    public JpaGameActionStore(GameRepository repository, GameSnapshotFactory snapshotFactory) {
        this.repository = repository;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public Optional<Match> findForUpdate(UUID gameId) {
        return repository.findByIdForUpdate(gameId).map(JpaGameActionStore::toDomain);
    }

    @Override
    public GameSnapshot save(Match match) {
        GameEntity entity = repository.findWithParticipantsById(match.id().value())
                .orElseThrow(() -> new IllegalStateException("Game disappeared while acting"));
        entity.applyMatchState(match);
        repository.flush();
        return snapshotFactory.snapshot(entity);
    }

    private static Match toDomain(GameEntity entity) {
        return Match.rehydrate(
                new GameId(entity.getId()),
                entity.getStatus(),
                new TimeControl(entity.getInitialTimeMs(), entity.getIncrementMs()),
                entity.getRevision(),
                entity.getParticipants().stream()
                        .map(participant -> new Game.Participant(
                                participant.getSide(), participant.getSessionId(), participant.getKind()))
                        .toList(),
                ChessPosition.fromFen(entity.getPositionFen()),
                parseHistory(entity.getMoveHistory()),
                entity.getLastMoveUci(),
                entity.getWhiteRemainingMs(),
                entity.getBlackRemainingMs(),
                entity.getActiveSide(),
                entity.getClockUpdatedAt(),
                entity.getDrawOfferSide(),
                entity.getResultOutcome(),
                entity.getResultReason());
    }

    private static MoveHistory parseHistory(String value) {
        if (value == null || value.isBlank()) {
            return MoveHistory.empty();
        }
        List<String> moves = Arrays.stream(value.trim().split("\\s+")).toList();
        MoveHistory history = MoveHistory.empty();
        for (String uci : moves) {
            history = history.append(Move.fromUci(uci));
        }
        return history;
    }
}
