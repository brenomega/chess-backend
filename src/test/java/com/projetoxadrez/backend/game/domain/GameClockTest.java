package com.projetoxadrez.backend.game.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GameClockTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");

    @Test
    void subtractsElapsedTimeOnlyFromTheActiveSide() {
        GameClock clock = new GameClock(
                GameStatus.ACTIVE,
                new TimeControl(600_000, 0),
                100_000,
                200_000,
                Side.WHITE,
                NOW.minusSeconds(15));

        GameClock.Reading reading = clock.applyElapsed(NOW);

        assertThat(reading).isEqualTo(new GameClock.Reading(
                GameStatus.ACTIVE, 85_000, 200_000, Side.WHITE, null));
    }

    @Test
    void detectsTimeoutAndReportsTheWinner() {
        GameClock clock = new GameClock(
                GameStatus.ACTIVE,
                new TimeControl(600_000, 0),
                100_000,
                200_000,
                Side.WHITE,
                NOW.minusSeconds(100));

        GameClock.Reading reading = clock.applyElapsed(NOW);

        assertThat(reading).isEqualTo(new GameClock.Reading(
                GameStatus.FINISHED, 0, 200_000, null, Side.BLACK));
        assertThat(reading.timedOut()).isTrue();
    }

    @Test
    void leavesAnInfiniteClockRunningWithoutTimeout() {
        GameClock clock = new GameClock(
                GameStatus.ACTIVE,
                new TimeControl(0, 0),
                0,
                0,
                Side.BLACK,
                NOW.minusSeconds(60));

        GameClock.Reading reading = clock.applyElapsed(NOW);

        assertThat(reading).isEqualTo(new GameClock.Reading(
                GameStatus.ACTIVE, 0, 0, Side.BLACK, null));
    }
}
