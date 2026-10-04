package com.projetoxadrez.backend.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameTest {

    private static final TimeControl TEN_MINUTES = new TimeControl(600_000, 0);
    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");

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

    @Test
    void joinsPublicGameAndStartsTheGameClock() {
        UUID joiningSessionId = UUID.randomUUID();
        Game game = waitingGame(GameVisibility.PUBLIC, null);

        game.join(joiningSessionId, null, NOW);

        assertThat(game.status()).isEqualTo(GameStatus.ACTIVE);
        assertThat(game.revision()).isEqualTo(1);
        assertThat(game.participants()).extracting(Game.Participant::side)
                .containsExactly(Side.WHITE, Side.BLACK);
        assertThat(game.participants().get(1).sessionId()).isEqualTo(joiningSessionId);
        assertThat(game.activeSide()).isEqualTo(Side.WHITE);
        assertThat(game.clockUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void requiresMatchingCodeForPrivateGameWithoutMutatingRejectedEntries() {
        Game game = waitingGame(GameVisibility.PRIVATE, new GameEntryCode("ABC234"));

        assertThatThrownBy(() -> game.join(UUID.randomUUID(), null, NOW))
                .isInstanceOf(Game.EntryCodeRequiredException.class);
        assertThatThrownBy(() -> game.join(UUID.randomUUID(), "XYZ789", NOW))
                .isInstanceOf(Game.InvalidEntryCodeException.class);

        assertThat(game.status()).isEqualTo(GameStatus.WAITING);
        assertThat(game.revision()).isZero();
        assertThat(game.participants()).hasSize(1);
        assertThat(game.activeSide()).isNull();
        assertThat(game.clockUpdatedAt()).isNull();
    }

    @Test
    void rejectsCodeForPublicGameWithoutMutatingIt() {
        Game game = waitingGame(GameVisibility.PUBLIC, null);

        assertThatThrownBy(() -> game.join(UUID.randomUUID(), "ABC234", NOW))
                .isInstanceOf(Game.UnexpectedEntryCodeException.class);

        assertThat(game.status()).isEqualTo(GameStatus.WAITING);
        assertThat(game.revision()).isZero();
        assertThat(game.participants()).hasSize(1);
    }

    @Test
    void rejectsNonWaitingFullAndDuplicateEntriesWithoutMutatingTheGame() {
        UUID creatorSessionId = UUID.randomUUID();
        Game activeGame = rehydratedGame(
                GameStatus.ACTIVE,
                List.of(new Game.Participant(Side.WHITE, creatorSessionId, "HUMAN")));
        Game fullGame = rehydratedGame(
                GameStatus.WAITING,
                List.of(
                        new Game.Participant(Side.WHITE, creatorSessionId, "HUMAN"),
                        new Game.Participant(Side.BLACK, UUID.randomUUID(), "HUMAN")));
        Game duplicateGame = rehydratedGame(
                GameStatus.WAITING,
                List.of(new Game.Participant(Side.WHITE, creatorSessionId, "HUMAN")));

        assertThatThrownBy(() -> activeGame.join(UUID.randomUUID(), null, NOW))
                .isInstanceOf(Game.GameNotJoinableException.class);
        assertThatThrownBy(() -> fullGame.join(UUID.randomUUID(), null, NOW))
                .isInstanceOf(Game.GameNotJoinableException.class);
        assertThatThrownBy(() -> duplicateGame.join(creatorSessionId, null, NOW))
                .isInstanceOf(Game.GameNotJoinableException.class);

        assertThat(activeGame.revision()).isZero();
        assertThat(fullGame.revision()).isZero();
        assertThat(duplicateGame.revision()).isZero();
        assertThat(activeGame.participants()).hasSize(1);
        assertThat(fullGame.participants()).hasSize(2);
        assertThat(duplicateGame.participants()).hasSize(1);
    }

    private static Game waitingGame(GameVisibility visibility, GameEntryCode entryCode) {
        return rehydratedGame(
                GameStatus.WAITING,
                visibility,
                entryCode,
                List.of(new Game.Participant(Side.WHITE, UUID.randomUUID(), "HUMAN")));
    }

    private static Game rehydratedGame(GameStatus status, List<Game.Participant> participants) {
        return rehydratedGame(status, GameVisibility.PUBLIC, null, participants);
    }

    private static Game rehydratedGame(
            GameStatus status,
            GameVisibility visibility,
            GameEntryCode entryCode,
            List<Game.Participant> participants) {
        return Game.rehydrate(
                GameId.newId(),
                status,
                visibility,
                entryCode,
                TEN_MINUTES,
                0,
                participants,
                status == GameStatus.ACTIVE ? Side.WHITE : null,
                status == GameStatus.ACTIVE ? NOW.minusSeconds(1) : null);
    }
}
