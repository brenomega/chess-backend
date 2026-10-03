package com.projetoxadrez.backend.game.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GameEntityEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");

    private GameEntity game;
    private UUID creatorSessionId;

    @BeforeEach
    void setUp() {
        creatorSessionId = UUID.randomUUID();
        game = waitingGame();
    }

    @Test
    void joinsEligibleGameAndStartsItsClock() {
        UUID joiningSessionId = UUID.randomUUID();

        game.join(joiningSessionId, NOW);

        assertThat(game.getStatus()).isEqualTo(GameStatus.ACTIVE);
        assertThat(game.getRevision()).isEqualTo(1);
        assertThat(game.getParticipants()).extracting(GameParticipantEntity::getSide)
                .containsExactly(Side.WHITE, Side.BLACK);
        assertThat(game.getParticipants().get(1).getSessionId()).isEqualTo(joiningSessionId);
        assertThat(game.getActiveSide()).isEqualTo(Side.WHITE);
        assertThat(game.getClockUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsNonJoinableStatesWithoutMutatingTheGame() {
        ReflectionTestUtils.setField(game, "status", GameStatus.ACTIVE);

        assertThatThrownBy(() -> game.join(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);

        assertThat(game.getStatus()).isEqualTo(GameStatus.ACTIVE);
        assertThat(game.getRevision()).isZero();
        assertThat(game.getParticipants()).hasSize(1);
        assertThat(game.getActiveSide()).isNull();
        assertThat(game.getClockUpdatedAt()).isNull();
    }

    @Test
    void rejectsFullGamesWithoutMutatingTheGame() {
        game.getParticipants().add(new GameParticipantEntity(game, Side.BLACK, UUID.randomUUID(), "HUMAN"));

        assertThatThrownBy(() -> game.join(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);

        assertThat(game.getStatus()).isEqualTo(GameStatus.WAITING);
        assertThat(game.getRevision()).isZero();
        assertThat(game.getParticipants()).hasSize(2);
        assertThat(game.getActiveSide()).isNull();
    }

    private GameEntity waitingGame() {
        GameEntity entity = new GameEntity();
        UUID gameId = UUID.randomUUID();
        set(entity, "id", gameId);
        set(entity, "status", GameStatus.WAITING);
        set(entity, "visibility", GameVisibility.PUBLIC);
        set(entity, "revision", 0L);
        set(entity, "participants", new ArrayList<GameParticipantEntity>());
        entity.getParticipants().add(new GameParticipantEntity(entity, Side.WHITE, creatorSessionId, "HUMAN"));
        return entity;
    }

    private static void set(GameEntity entity, String field, Object value) {
        ReflectionTestUtils.setField(entity, field, value);
    }
}
