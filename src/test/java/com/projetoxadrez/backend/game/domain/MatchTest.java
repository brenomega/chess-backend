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

/** EAP 2.16.2: regras, transições, relógio e concorrência das ações da partida. */
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

    // ---------- 2.9.1 lance ----------

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
        // Mate do pastor: as brancas jogam Dxf7#.
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

    // ---------- 2.11.1 revisão e idempotência ----------

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

        // O reenvio chega com a revisão antiga. Como o lance já foi aplicado, a vez passou para as
        // pretas e a repetição é recusada sem alterar a partida.
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

    // ---------- 2.13.1 autorização ----------

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

    // ---------- 2.9.2 abandono ----------

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

    // ---------- 2.9.3 empate ----------

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

    // ---------- invariante do relógio ----------

    /**
     * A tabela game exige que lado da vez e âncora do relógio sejam nulos juntos
     * (game_clock_anchor_consistent), então toda forma de encerramento precisa zerar os dois.
     */
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

    // ---------- 2.10 relógio ----------

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
}
