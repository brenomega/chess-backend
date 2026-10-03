package com.projetoxadrez.backend.game.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JpaGameEntryStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");

    @Test
    void joinsPrivateGameWithMatchingCode() {
        GameEntity game = waitingPrivateGame();
        JpaGameEntryStore store = storeFor(game);

        GameSnapshot snapshot = store.join(game.getId(), UUID.randomUUID(), "ABC234", NOW);

        assertThat(snapshot.status()).isEqualTo(GameStatus.ACTIVE);
        assertThat(snapshot.entryCode()).isEqualTo("ABC234");
        assertThat(snapshot.players()).extracting(GameSnapshot.Player::side)
                .containsExactly(Side.WHITE, Side.BLACK);
    }

    @Test
    void rejectsMissingAndInvalidPrivateCodesWithoutMutatingTheGame() {
        GameEntity game = waitingPrivateGame();
        JpaGameEntryStore store = storeFor(game);

        assertThatThrownBy(() -> store.join(game.getId(), UUID.randomUUID(), null, NOW))
                .isInstanceOf(GameEntryService.EntryCodeRequiredException.class);
        assertThatThrownBy(() -> store.join(game.getId(), UUID.randomUUID(), "XYZ789", NOW))
                .isInstanceOf(GameEntryService.InvalidEntryCodeException.class);

        assertThat(game.getStatus()).isEqualTo(GameStatus.WAITING);
        assertThat(game.getRevision()).isZero();
        assertThat(game.getParticipants()).hasSize(1);
        assertThat(game.getActiveSide()).isNull();
    }

    private static JpaGameEntryStore storeFor(GameEntity game) {
        return new JpaGameEntryStore(
                gameId -> gameId.equals(game.getId()) ? Optional.of(game) : Optional.empty(),
                new GameSnapshotFactory(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static GameEntity waitingPrivateGame() {
        GameEntity game = new GameEntity();
        UUID gameId = UUID.randomUUID();
        set(game, "id", gameId);
        set(game, "status", GameStatus.WAITING);
        set(game, "visibility", GameVisibility.PRIVATE);
        set(game, "entryCode", "ABC234");
        set(game, "revision", 0L);
        set(game, "positionFen", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        set(game, "whiteRemainingMs", 600_000L);
        set(game, "blackRemainingMs", 600_000L);
        set(game, "initialTimeMs", 600_000L);
        set(game, "incrementMs", 0L);
        set(game, "participants", new ArrayList<GameParticipantEntity>());
        game.getParticipants().add(new GameParticipantEntity(game, Side.WHITE, UUID.randomUUID(), "HUMAN"));
        return game;
    }

    private static void set(GameEntity game, String field, Object value) {
        ReflectionTestUtils.setField(game, field, value);
    }
}
