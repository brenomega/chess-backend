package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BoardTest {

    @Test
    void createsStandardInitialBoard() {
        Board board = Board.initial();

        assertThat(board.pieceCount()).isEqualTo(32);
        assertThat(board.pieceAt(Square.fromAlgebraic("a1")))
                .contains(new Piece(Side.WHITE, PieceType.ROOK));
        assertThat(board.pieceAt(Square.fromAlgebraic("e1")))
                .contains(new Piece(Side.WHITE, PieceType.KING));
        assertThat(board.pieceAt(Square.fromAlgebraic("d8")))
                .contains(new Piece(Side.BLACK, PieceType.QUEEN));
        assertThat(board.pieceAt(Square.fromAlgebraic("h7")))
                .contains(new Piece(Side.BLACK, PieceType.PAWN));
    }

    @Test
    void addingAndRemovingPieceCreatesNewBoards() {
        Square e4 = Square.fromAlgebraic("e4");
        Piece queen = new Piece(Side.WHITE, PieceType.QUEEN);
        Board empty = Board.empty();

        Board occupied = empty.withPiece(e4, queen);
        Board cleared = occupied.withoutPiece(e4);

        assertThat(empty.pieceAt(e4)).isEmpty();
        assertThat(occupied.pieceAt(e4)).contains(queen);
        assertThat(cleared.pieceAt(e4)).isEmpty();
    }

    @Test
    void doesNotReplacePieceDuringBoardSetup() {
        Board board = Board.empty().withPiece(
                Square.fromAlgebraic("e4"),
                new Piece(Side.WHITE, PieceType.KING));

        assertThatThrownBy(() -> board.withPiece(
                Square.fromAlgebraic("e4"),
                new Piece(Side.BLACK, PieceType.KING)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
