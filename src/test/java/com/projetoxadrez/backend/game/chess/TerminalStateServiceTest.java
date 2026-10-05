package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalStateServiceTest {

    private final TerminalStateService service = new TerminalStateService();

    @Test
    void detectsCheckmateAndWinningSide() {
        ChessPosition foolsMate = ChessPosition.initial()
                .apply(Move.fromUci("f2f3"))
                .apply(Move.fromUci("e7e5"))
                .apply(Move.fromUci("g2g4"))
                .apply(Move.fromUci("d8h4"));
        ChessPosition whiteCheckmate = ChessPosition.fromFen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");

        assertThat(service.result(foolsMate)).contains(ChessResult.BLACK_WIN_BY_CHECKMATE);
        assertThat(service.result(whiteCheckmate)).contains(ChessResult.WHITE_WIN_BY_CHECKMATE);
        assertThat(foolsMate.legalMoves()).isEmpty();
        assertThat(foolsMate.isInCheck(Side.WHITE)).isTrue();
    }

    @Test
    void detectsStalemate() {
        ChessPosition stalemate = ChessPosition.fromFen("7k/5K2/6Q1/8/8/8/8/8 b - - 0 1");

        assertThat(service.result(stalemate)).contains(ChessResult.DRAW_BY_STALEMATE);
        assertThat(stalemate.legalMoves()).isEmpty();
        assertThat(stalemate.isInCheck(Side.BLACK)).isFalse();
    }

    @Test
    void detectsInsufficientMaterialWithKingsAndOneMinorPiece() {
        ChessPosition kingsOnly = ChessPosition.fromFen("8/8/8/3k4/8/4K3/8/8 w - - 0 1");
        ChessPosition bishop = ChessPosition.fromFen("8/8/8/3k4/8/4K3/2B5/8 w - - 0 1");
        ChessPosition knight = ChessPosition.fromFen("8/8/8/3k4/8/4K3/2N5/8 w - - 0 1");

        assertThat(service.result(kingsOnly)).contains(ChessResult.DRAW_BY_INSUFFICIENT_MATERIAL);
        assertThat(service.result(bishop)).contains(ChessResult.DRAW_BY_INSUFFICIENT_MATERIAL);
        assertThat(service.result(knight)).contains(ChessResult.DRAW_BY_INSUFFICIENT_MATERIAL);
    }

    @Test
    void detectsInsufficientMaterialWhenAllBishopsUseSameSquareColor() {
        ChessPosition position = ChessPosition.fromFen("7k/8/5b2/8/3B4/8/1B6/K7 w - - 0 1");

        assertThat(service.result(position)).contains(ChessResult.DRAW_BY_INSUFFICIENT_MATERIAL);
    }

    @Test
    void doesNotEndPositionWithMatingMaterial() {
        ChessPosition oppositeColorBishops = ChessPosition.fromFen("7k/8/4b3/8/3B4/8/8/K7 w - - 0 1");
        ChessPosition twoKnights = ChessPosition.fromFen("7k/8/8/8/8/8/2NN4/K7 w - - 0 1");
        ChessPosition rook = ChessPosition.fromFen("7k/8/8/8/8/8/2R5/K7 w - - 0 1");

        assertThat(service.result(oppositeColorBishops)).isEmpty();
        assertThat(service.result(twoKnights)).isEmpty();
        assertThat(service.result(rook)).isEmpty();
    }
}
