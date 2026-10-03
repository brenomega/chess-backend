package com.projetoxadrez.backend.game.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JpaGameQueryStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");

    private JpaGameQueryStore store;
    private GameEntity game;

    @BeforeEach
    void setUp() {
        store = new JpaGameQueryStore(null, new GameSnapshotFactory(Clock.fixed(NOW, ZoneOffset.UTC)));
        game = new GameEntity();
        set("id", UUID.randomUUID());
        set("status", GameStatus.ACTIVE);
        set("visibility", GameVisibility.PUBLIC);
        set("positionFen", "8/8/8/8/8/8/8/8 w - - 0 1");
        set("initialTimeMs", 600_000L);
    }

    @Test
    void subtractsElapsedTimeFromTheActiveSide() {
        set("whiteRemainingMs", 100_000L);
        set("blackRemainingMs", 200_000L);
        set("activeSide", Side.WHITE);
        set("clockUpdatedAt", NOW.minusSeconds(15));

        GameSnapshot snapshot = store.snapshot(game);

        assertThat(snapshot.status()).isEqualTo(GameStatus.ACTIVE);
        assertThat(snapshot.clock()).isEqualTo(new GameSnapshot.Clock(85_000, 200_000, Side.WHITE));
        assertThat(snapshot.result()).isNull();
    }

    @Test
    void returnsFinishedSnapshotWhenTheActiveSideRunsOutOfTime() {
        set("whiteRemainingMs", 100_000L);
        set("blackRemainingMs", 200_000L);
        set("activeSide", Side.WHITE);
        set("clockUpdatedAt", NOW.minusSeconds(100));
        set("drawOfferSide", Side.BLACK);

        GameSnapshot snapshot = store.snapshot(game);

        assertThat(snapshot.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(snapshot.clock()).isEqualTo(new GameSnapshot.Clock(0, 200_000, null));
        assertThat(snapshot.drawOffer()).isNull();
        assertThat(snapshot.result()).isEqualTo(new GameSnapshot.Result("BLACK_WIN", "TIMEOUT"));
    }

    @Test
    void doesNotRunAClockForUnlimitedGames() {
        set("initialTimeMs", 0L);
        set("whiteRemainingMs", 0L);
        set("blackRemainingMs", 0L);
        set("activeSide", Side.BLACK);
        set("clockUpdatedAt", NOW.minusSeconds(60));

        GameSnapshot snapshot = store.snapshot(game);

        assertThat(snapshot.status()).isEqualTo(GameStatus.ACTIVE);
        assertThat(snapshot.clock()).isEqualTo(new GameSnapshot.Clock(0, 0, Side.BLACK));
        assertThat(snapshot.result()).isNull();
    }

    private void set(String field, Object value) {
        ReflectionTestUtils.setField(game, field, value);
    }
}
