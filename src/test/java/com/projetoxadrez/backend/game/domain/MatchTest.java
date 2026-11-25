package com.projetoxadrez.backend.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.chess.MoveHistory;
import com.projetoxadrez.backend.game.chess.Side;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchTest {

    private static final TimeControl TEN_MINUTES = new TimeControl(600_000, 0);
    private static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");
    private static final UUID WHITE_SESSION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLACK_SESSION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STRANGER_SESSION = UUID.fromString("00000000-0000-0000-0000-000000000009");

    private static Match active(TimeControl timeControl) {
        return activeFrom(ChessPosition.initial().toFen(), timeControl, 600_000, 600_000);
    }

    private static Match activeFrom(String fen, TimeControl timeControl, long whiteMs, long blackMs) {
        ChessPosition position = ChessPosition.fromFen(fen);
        return Match.rehydrate(
                new GameId(UUID.randomUUID()),
                GameStatus.ACTIVE,
                timeControl,
                7,
                List.of(
                        new Game.Participant(Side.WHITE, WHITE_SESSION, "HUMAN"),
                        new Game.Participant(Side.BLACK, BLACK_SESSION, "HUMAN")),
                position,
                MoveHistory.empty(),
                null,
                whiteMs,
                blackMs,
                position.sideToMove(),
                NOW,
                null,
                null,
                null);
    }

    private static Match withParticipants(List<Game.Participant> participants) {
        ChessPosition position = ChessPosition.initial();
        return Match.rehydrate(
                new GameId(UUID.randomUUID()),
                GameStatus.ACTIVE,
                TEN_MINUTES,
                7,
                participants,
                position,
                MoveHistory.empty(),
                null,
                600_000,
                600_000,
                position.sideToMove(),
                NOW,
                null,
                null,
                null);
    }

    private static Match againstAi() {
        return withParticipants(List.of(
                new Game.Participant(Side.WHITE, WHITE_SESSION, "HUMAN"),
                new Game.Participant(Side.BLACK, null, "AI")));
    }

    private static Match aiVersusAi() {
        return withParticipants(List.of(
                new Game.Participant(Side.WHITE, null, "AI"), new Game.Participant(Side.BLACK, null, "AI")));
    }

    @Test
    void appliesLegalMoveAndAdvancesStateHistoryAndRevision() {
        Match match = active(TEN_MINUTES);

        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW.plusSeconds(10));

        assertThat(match.lastMoveUci()).isEqualTo("e2e4");
        assertThat(match.history().uciMoves()).containsExactly("e2e4");
        assertThat(match.activeSide()).isEqualTo(Side.BLACK);
        assertThat(match.revision()).isEqualTo(8);
        assertThat(match.position().toFen()).startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b");
        assertThat(match.status()).isEqualTo(GameStatus.ACTIVE);
    }

    @Test
    void rejectsIllegalMoveWithoutChangingTheGame() {
        Match match = active(TEN_MINUTES);

        assertThatThrownBy(() -> match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e5"), NOW))
                .isInstanceOf(Match.IllegalMoveException.class);

        assertThat(match.revision()).isEqualTo(7);
        assertThat(match.lastMoveUci()).isNull();
        assertThat(match.history().uciMoves()).isEmpty();
        assertThat(match.position().toFen()).isEqualTo(ChessPosition.initial().toFen());
    }

    @Test
    void rejectsMoveFromTheSideThatIsNotOnTurn() {
        Match match = active(TEN_MINUTES);

        assertThatThrownBy(() -> match.applyMove(BLACK_SESSION, 7, Move.fromUci("e7e5"), NOW))
                .isInstanceOf(Match.NotYourTurnException.class);
        assertThat(match.revision()).isEqualTo(7);
    }

    @Test
    void finishesByCheckmateWhenTheMoveDeliversIt() {
        Match match = activeFrom(
                "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                TEN_MINUTES,
                600_000,
                600_000);

        match.applyMove(WHITE_SESSION, 7, Move.fromUci("f3f7"), NOW);

        assertThat(match.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(match.resultOutcome()).isEqualTo("WHITE_WIN");
        assertThat(match.resultReason()).isEqualTo("CHECKMATE");
        assertThat(match.activeSide()).isNull();
    }

    @Test
    void rejectsActionCarryingAnOutdatedRevision() {
        Match match = active(TEN_MINUTES);

        assertThatThrownBy(() -> match.applyMove(WHITE_SESSION, 6, Move.fromUci("e2e4"), NOW))
                .isInstanceOf(Match.StaleRevisionException.class);
        assertThat(match.revision()).isEqualTo(7);
    }

    @Test
    void repeatedMoveIsRejectedAndNeverAppliedTwice() {
        Match match = active(TEN_MINUTES);
        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW);

        assertThatThrownBy(() -> match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW))
                .isInstanceOf(Match.NotYourTurnException.class);
        assertThat(match.history().uciMoves()).containsExactly("e2e4");
        assertThat(match.revision()).isEqualTo(8);
    }

    @Test
    void repeatedDrawOfferWithOldRevisionIsRejected() {
        Match match = active(TEN_MINUTES);
        match.offerDraw(WHITE_SESSION, 7, NOW);

        assertThatThrownBy(() -> match.offerDraw(WHITE_SESSION, 7, NOW))
                .isInstanceOf(Match.StaleRevisionException.class);
        assertThat(match.revision()).isEqualTo(8);
        assertThat(match.drawOfferSide()).isEqualTo(Side.WHITE);
    }

    @Test
    void acceptsActionWithoutRevisionCheck() {
        Match match = active(TEN_MINUTES);

        match.applyMove(WHITE_SESSION, -1, Move.fromUci("e2e4"), NOW);

        assertThat(match.revision()).isEqualTo(8);
    }

    @Test
    void rejectsActionsFromSessionsThatDoNotPlayTheGame() {
        Match match = active(TEN_MINUTES);

        assertThatThrownBy(() -> match.applyMove(STRANGER_SESSION, 7, Move.fromUci("e2e4"), NOW))
                .isInstanceOf(Match.NotAParticipantException.class);
        assertThatThrownBy(() -> match.abandon(STRANGER_SESSION, NOW))
                .isInstanceOf(Match.NotAParticipantException.class);
        assertThatThrownBy(() -> match.offerDraw(STRANGER_SESSION, 7, NOW))
                .isInstanceOf(Match.NotAParticipantException.class);
        assertThat(match.revision()).isEqualTo(7);
    }

    @Test
    void abandonGivesTheWinToTheOpponent() {
        Match match = active(TEN_MINUTES);

        match.abandon(WHITE_SESSION, NOW);

        assertThat(match.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(match.resultOutcome()).isEqualTo("BLACK_WIN");
        assertThat(match.resultReason()).isEqualTo("RESIGNATION");
        assertThat(match.revision()).isEqualTo(8);
    }

    @Test
    void rejectsActionsOnAGameThatIsNotActive() {
        Match match = active(TEN_MINUTES);
        match.abandon(WHITE_SESSION, NOW);

        assertThatThrownBy(() -> match.applyMove(BLACK_SESSION, match.revision(), Move.fromUci("e7e5"), NOW))
                .isInstanceOf(Match.InvalidGameStateException.class);
    }

    @Test
    void registersDrawOfferAndEndsTheGameWhenAccepted() {
        Match match = active(TEN_MINUTES);

        match.offerDraw(WHITE_SESSION, 7, NOW);
        assertThat(match.drawOfferSide()).isEqualTo(Side.WHITE);
        assertThat(match.status()).isEqualTo(GameStatus.ACTIVE);

        match.respondDraw(BLACK_SESSION, match.revision(), true, NOW);

        assertThat(match.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(match.resultOutcome()).isEqualTo("DRAW");
        assertThat(match.resultReason()).isEqualTo("DRAW_AGREED");
        assertThat(match.drawOfferSide()).isNull();
    }

    @Test
    void declinedDrawClearsTheOfferAndKeepsTheGameRunning() {
        Match match = active(TEN_MINUTES);
        match.offerDraw(WHITE_SESSION, 7, NOW);

        match.respondDraw(BLACK_SESSION, match.revision(), false, NOW);

        assertThat(match.drawOfferSide()).isNull();
        assertThat(match.status()).isEqualTo(GameStatus.ACTIVE);
    }

    @Test
    void rejectsResponseWhenThereIsNoPendingOffer() {
        Match match = active(TEN_MINUTES);

        assertThatThrownBy(() -> match.respondDraw(BLACK_SESSION, 7, true, NOW))
                .isInstanceOf(Match.NoDrawOfferException.class);
    }

    @Test
    void movingClearsAPendingDrawOffer() {
        Match match = active(TEN_MINUTES);
        match.offerDraw(BLACK_SESSION, 7, NOW);

        match.applyMove(WHITE_SESSION, match.revision(), Move.fromUci("e2e4"), NOW);

        assertThat(match.drawOfferSide()).isNull();
    }

    @Test
    void everyEndingStopsTheClockAnchorAndRecordsWhenItEnded() {
        Match active = active(TEN_MINUTES);
        assertThat(active.activeSide()).isNotNull();
        assertThat(active.clockUpdatedAt()).isNotNull();
        assertThat(active.endedAt()).isNull();

        Match resigned = active(TEN_MINUTES);
        resigned.abandon(WHITE_SESSION, NOW);
        assertThat(resigned.activeSide()).isNull();
        assertThat(resigned.clockUpdatedAt()).isNull();
        assertThat(resigned.endedAt()).isEqualTo(NOW);

        Match agreed = active(TEN_MINUTES);
        agreed.offerDraw(WHITE_SESSION, 7, NOW);
        agreed.respondDraw(BLACK_SESSION, agreed.revision(), true, NOW);
        assertThat(agreed.clockUpdatedAt()).isNull();
        assertThat(agreed.endedAt()).isNotNull();

        Match mated = activeFrom(
                "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                TEN_MINUTES,
                600_000,
                600_000);
        mated.applyMove(WHITE_SESSION, 7, Move.fromUci("f3f7"), NOW);
        assertThat(mated.clockUpdatedAt()).isNull();
        assertThat(mated.endedAt()).isEqualTo(NOW);

        Match flagged = activeFrom(ChessPosition.initial().toFen(), TEN_MINUTES, 1_000, 600_000);
        flagged.settleTimeout(NOW.plus(Duration.ofSeconds(5)));
        assertThat(flagged.clockUpdatedAt()).isNull();
        assertThat(flagged.endedAt()).isEqualTo(NOW.plus(Duration.ofSeconds(5)));
    }

    @Test
    void chargesElapsedTimeToTheSideOnTurn() {
        Match match = active(TEN_MINUTES);

        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW.plus(Duration.ofSeconds(30)));

        assertThat(match.whiteRemainingMs()).isEqualTo(570_000);
        assertThat(match.blackRemainingMs()).isEqualTo(600_000);
    }

    @Test
    void finishesOnTimeoutAndRejectsTheLateMove() {
        Match match = activeFrom(ChessPosition.initial().toFen(), TEN_MINUTES, 5_000, 600_000);

        assertThatThrownBy(() ->
                        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW.plus(Duration.ofSeconds(10))))
                .isInstanceOf(Match.GameTimedOutException.class);

        assertThat(match.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(match.resultOutcome()).isEqualTo("BLACK_WIN");
        assertThat(match.resultReason()).isEqualTo("TIMEOUT");
        assertThat(match.whiteRemainingMs()).isZero();
    }

    @Test
    void settleTimeoutEndsTheGameWithoutAnyAction() {
        Match match = activeFrom(ChessPosition.initial().toFen(), TEN_MINUTES, 1_000, 600_000);

        boolean ended = match.settleTimeout(NOW.plus(Duration.ofSeconds(5)));

        assertThat(ended).isTrue();
        assertThat(match.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(match.resultReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void infiniteTimeControlNeverTimesOut() {
        Match match = activeFrom(
                ChessPosition.initial().toFen(),
                new TimeControl(TimeControl.INFINITE_INITIAL_TIME_MS, 0),
                0,
                0);

        assertThat(match.settleTimeout(NOW.plus(Duration.ofHours(5)))).isFalse();
        assertThat(match.status()).isEqualTo(GameStatus.ACTIVE);
    }

    @Test
    void appliesAiMoveForTheSideTheAiControls() {
        Match match = againstAi();
        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW);

        match.applyAiMove(Side.BLACK, match.revision(), Move.fromUci("e7e5"), NOW);

        assertThat(match.history().uciMoves()).containsExactly("e2e4", "e7e5");
        assertThat(match.activeSide()).isEqualTo(Side.WHITE);
        assertThat(match.revision()).isEqualTo(9);
    }

    @Test
    void refusesAiMoveOnASidePlayedByAHuman() {
        Match match = againstAi();

        assertThatThrownBy(() -> match.applyAiMove(Side.WHITE, 7, Move.fromUci("e2e4"), NOW))
                .isInstanceOf(Match.NotAParticipantException.class);
        assertThat(match.revision()).isEqualTo(7);
    }

    @Test
    void rejectsHumanActionsWithNoSessionInAnAiGame() {
        Match match = aiVersusAi();

        assertThatThrownBy(() -> match.applyMove(null, 7, Move.fromUci("e2e4"), NOW))
                .isInstanceOf(Match.NotAParticipantException.class);
        assertThat(match.history().uciMoves()).isEmpty();
        assertThat(match.revision()).isEqualTo(7);
    }

    @Test
    void holdsTheAiToTheSameRulesAsAHuman() {
        Match match = againstAi();

        assertThatThrownBy(() -> match.applyAiMove(Side.BLACK, 7, Move.fromUci("e7e5"), NOW))
                .isInstanceOf(Match.NotYourTurnException.class);

        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW);

        assertThatThrownBy(() -> match.applyAiMove(Side.BLACK, match.revision(), Move.fromUci("e7e4"), NOW))
                .isInstanceOf(Match.IllegalMoveException.class);
        assertThatThrownBy(() -> match.applyAiMove(Side.BLACK, 7, Move.fromUci("e7e5"), NOW))
                .isInstanceOf(Match.StaleRevisionException.class);
        assertThat(match.history().uciMoves()).containsExactly("e2e4");
    }

    @Test
    void reportsWhichSideTheAiMustPlayNow() {
        Match match = againstAi();
        assertThat(match.aiSideToMove()).isEmpty();

        match.applyMove(WHITE_SESSION, 7, Move.fromUci("e2e4"), NOW);
        assertThat(match.aiSideToMove()).contains(Side.BLACK);

        match.applyAiMove(Side.BLACK, match.revision(), Move.fromUci("e7e5"), NOW);
        assertThat(match.aiSideToMove()).isEmpty();
    }

    @Test
    void alternatesBetweenTwoAisAndStopsWhenTheGameEnds() {
        Match match = aiVersusAi();

        assertThat(match.aiSideToMove()).contains(Side.WHITE);
        match.applyAiMove(Side.WHITE, match.revision(), Move.fromUci("e2e4"), NOW);
        assertThat(match.aiSideToMove()).contains(Side.BLACK);
        match.applyAiMove(Side.BLACK, match.revision(), Move.fromUci("e7e5"), NOW);
        assertThat(match.aiSideToMove()).contains(Side.WHITE);
        assertThat(match.history().uciMoves()).containsExactly("e2e4", "e7e5");
    }

    @Test
    void stopsAskingTheAiOnceTheGameIsOver() {
        Match match = againstAi();
        match.abandon(WHITE_SESSION, NOW);

        assertThat(match.aiSideToMove()).isEmpty();
        assertThatThrownBy(() -> match.applyAiMove(Side.BLACK, match.revision(), Move.fromUci("e7e5"), NOW))
                .isInstanceOf(Match.InvalidGameStateException.class);
    }
}
