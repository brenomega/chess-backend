package com.projetoxadrez.backend.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameTest {

    private static final TimeControl TEN_MINUTES = new TimeControl(600_000, 0);

    @Test
    void createsPublicGameWithIdentifierConfigurationAndWaitingState() {
        Game game = Game.create(GameVisibility.PUBLIC, TEN_MINUTES);

        assertThat(game.id().value()).isNotNull();
        assertThat(game.status()).isEqualTo(GameStatus.WAITING);
        assertThat(game.visibility()).isEqualTo(GameVisibility.PUBLIC);
        assertThat(game.entryCode()).isEmpty();
        assertThat(game.timeControl()).isEqualTo(TEN_MINUTES);
    }

    @Test
    void createsPrivateGameWithEntryCode() {
        Game game = Game.create(GameVisibility.PRIVATE, TEN_MINUTES);

        assertThat(game.status()).isEqualTo(GameStatus.WAITING);
        assertThat(game.visibility()).isEqualTo(GameVisibility.PRIVATE);
        assertThat(game.entryCode()).hasValueSatisfying(code ->
                assertThat(code.value()).matches("[A-HJ-NP-Z2-9]{6}"));
    }

    @Test
    void rejectsMissingConfigurationAndInvalidEntryCodes() {
        assertThatThrownBy(() -> Game.create(null, TEN_MINUTES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Game.create(GameVisibility.PUBLIC, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GameEntryCode("ABC123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameEntryCode("abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameEntryCode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 180_000, 600_000, 3_600_000})
    void acceptsSupportedTimeControls(long initialTimeMs) {
        TimeControl timeControl = new TimeControl(initialTimeMs, 0);

        Game game = Game.create(GameVisibility.PUBLIC, timeControl);

        assertThat(game.timeControl()).isEqualTo(timeControl);
        assertThat(timeControl.isInfinite()).isEqualTo(initialTimeMs == 0);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1, 60_000, 300_000, 601_000})
    void rejectsUnsupportedInitialTimes(long initialTimeMs) {
        assertThatThrownBy(() -> new TimeControl(initialTimeMs, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTimeControlIncrement() {
        assertThatThrownBy(() -> new TimeControl(600_000, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionsFromWaitingToActiveAndFinished() {
        Game game = Game.create(GameVisibility.PUBLIC, TEN_MINUTES);

        game.activate();
        assertThat(game.status()).isEqualTo(GameStatus.ACTIVE);

        game.finish();
        assertThat(game.status()).isEqualTo(GameStatus.FINISHED);
    }

    @Test
    void abandonsWaitingOrActiveGames() {
        Game waitingGame = Game.create(GameVisibility.PUBLIC, TEN_MINUTES);
        Game activeGame = Game.create(GameVisibility.PUBLIC, TEN_MINUTES);
        activeGame.activate();

        waitingGame.abandon();
        activeGame.abandon();

        assertThat(waitingGame.status()).isEqualTo(GameStatus.ABANDONED);
        assertThat(activeGame.status()).isEqualTo(GameStatus.ABANDONED);
    }

    @Test
    void rejectsInvalidTransitionsWithoutChangingCurrentState() {
        Game waitingGame = Game.create(GameVisibility.PUBLIC, TEN_MINUTES);
        assertThatThrownBy(waitingGame::finish)
                .isInstanceOf(IllegalStateException.class);
        assertThat(waitingGame.status()).isEqualTo(GameStatus.WAITING);

        waitingGame.activate();
        assertThatThrownBy(waitingGame::activate)
                .isInstanceOf(IllegalStateException.class);
        assertThat(waitingGame.status()).isEqualTo(GameStatus.ACTIVE);

        waitingGame.finish();
        assertThatThrownBy(waitingGame::abandon)
                .isInstanceOf(IllegalStateException.class);
        assertThat(waitingGame.status()).isEqualTo(GameStatus.FINISHED);

        Game abandonedGame = Game.create(GameVisibility.PUBLIC, TEN_MINUTES);
        abandonedGame.abandon();
        assertThatThrownBy(abandonedGame::activate)
                .isInstanceOf(IllegalStateException.class);
        assertThat(abandonedGame.status()).isEqualTo(GameStatus.ABANDONED);
    }
}
