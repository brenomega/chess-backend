package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FenSerializationTest {

    @Test
    void serializesInitialPosition() {
        assertThat(ChessPosition.initial().toFen())
                .isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    @Test
    void parsesAndSerializesAllFenFields() {
        String fen = "r3k2r/ppp2ppp/2n5/3pp3/3PP3/2N5/PPP2PPP/R3K2R b KQkq e3 17 24";

        ChessPosition position = ChessPosition.fromFen(fen);

        assertThat(position.toFen()).isEqualTo(fen);
        assertThat(position.sideToMove()).isEqualTo(Side.BLACK);
        assertThat(position.castlingRights()).isEqualTo(CastlingRights.initial());
        assertThat(position.enPassantTarget()).isEqualTo(Square.fromAlgebraic("e3"));
        assertThat(position.halfmoveClock()).isEqualTo(17);
        assertThat(position.fullmoveNumber()).isEqualTo(24);
    }

    @Test
    void updatesFenAfterMoves() {
        ChessPosition position = ChessPosition.initial()
                .apply(Move.fromUci("e2e4"))
                .apply(Move.fromUci("g8f6"))
                .apply(Move.fromUci("g1f3"));

        assertThat(position.toFen())
                .isEqualTo("rnbqkb1r/pppppppp/5n2/8/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 2 2");
    }

    @Test
    void resetsHalfmoveClockOnCaptureAndIncrementsFullmoveAfterBlackMove() {
        ChessPosition position = ChessPosition.fromFen(
                        "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 4 7")
                .apply(Move.fromUci("g8f6"))
                .apply(Move.fromUci("f3e5"));

        assertThat(position.halfmoveClock()).isZero();
        assertThat(position.fullmoveNumber()).isEqualTo(8);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "",
        "8/8/8/8/8/8/8/8 w - - 0",
        "8/8/8/8/8/8/8/7X w - - 0 1",
        "8/8/8/8/8/8/8/9 w - - 0 1",
        "8/8/8/8/8/8/8/8 x - - 0 1",
        "8/8/8/8/8/8/8/8 w qK - 0 1",
        "8/8/8/8/8/8/8/8 w - e3 0 1",
        "8/8/8/8/8/8/8/8 w - - -1 1",
        "8/8/8/8/8/8/8/8 w - - 0 0"
    })
    void rejectsInvalidFen(String fen) {
        assertThatThrownBy(() -> ChessPosition.fromFen(fen))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
