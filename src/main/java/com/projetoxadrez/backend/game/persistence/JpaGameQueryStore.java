package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameQueryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class JpaGameQueryStore implements GameQueryStore {

    private final GameRepository repository;

    public JpaGameQueryStore(GameRepository repository) {
        this.repository = repository;
    }

    @Override
    public Page<LobbyGame> findPublicWaitingGames(Pageable pageable) {
        return repository.findByStatusAndVisibility(GameStatus.WAITING, GameVisibility.PUBLIC, pageable)
                .map(this::lobbyGame);
    }

    @Override
    public Optional<GameSnapshot> findSnapshot(UUID gameId, UUID sessionId) {
        return repository.findWithParticipantsById(gameId)
                .filter(game -> game.getParticipants().stream()
                        .anyMatch(participant -> sessionId.equals(participant.getSessionId())))
                .map(this::snapshot);
    }

    @Override
    public boolean existsById(UUID gameId) {
        return repository.existsById(gameId);
    }

    private LobbyGame lobbyGame(GameEntity game) {
        return new LobbyGame(
                game.getId(),
                game.getStatus(),
                game.getVisibility(),
                new TimeControl(game.getInitialTimeMs(), game.getIncrementMs()),
                game.getCreatedAt());
    }

    private GameSnapshot snapshot(GameEntity game) {
        return new GameSnapshot(
                game.getId(),
                game.getStatus(),
                game.getVisibility(),
                game.getEntryCode(),
                game.getRevision(),
                game.getParticipants().stream()
                        .map(participant -> new GameSnapshot.Player(participant.getSide(), participant.getKind()))
                        .toList(),
                new GameSnapshot.Position(
                        game.getPositionFen(), sideToMove(game.getPositionFen()), game.getLastMoveUci()),
                new GameSnapshot.Clock(
                        game.getWhiteRemainingMs(), game.getBlackRemainingMs(), game.getActiveSide()),
                game.getDrawOfferSide() == null ? null : new GameSnapshot.DrawOffer(game.getDrawOfferSide()),
                game.getResultOutcome() == null
                        ? null
                        : new GameSnapshot.Result(game.getResultOutcome(), game.getResultReason()));
    }

    private static Side sideToMove(String fen) {
        String[] parts = fen.split(" ");
        if (parts.length < 2) {
            throw new IllegalStateException("Stored game position is invalid");
        }
        return switch (parts[1]) {
            case "w" -> Side.WHITE;
            case "b" -> Side.BLACK;
            default -> throw new IllegalStateException("Stored game position is invalid");
        };
    }
}
