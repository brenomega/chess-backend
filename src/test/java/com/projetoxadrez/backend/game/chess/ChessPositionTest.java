package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChessPositionTest {

    @ParameterizedTest
    @MethodSource("basicMoves")
    void movesEveryPieceType(PieceType type, String from, String to) {
        Piece piece = new Piece(Side.WHITE, type);
        ChessPosition position = positionWith(piece, from);

        ChessPosition result = position.apply(Move.between(from, to));

        assertThat(result.board().pieceAt(Square.fromAlgebraic(from))).isEmpty();
        assertThat(result.board().pieceAt(Square.fromAlgebraic(to))).contains(piece);
        assertThat(result.sideToMove()).isEqualTo(Side.BLACK);
    }

    @ParameterizedTest
    @MethodSource("basicCaptures")
    void capturesWithEveryPieceType(PieceType type, String from, String to) {
        Piece attacker = new Piece(Side.WHITE, type);
        Board board = Board.empty()
                .withPiece(Square.fromAlgebraic(from), attacker)
                .withPiece(Square.fromAlgebraic(to), new Piece(Side.BLACK, PieceType.ROOK));

        ChessPosition result = new ChessPosition(board, Side.WHITE).apply(Move.between(from, to));

        assertThat(result.board().pieceAt(Square.fromAlgebraic(to))).contains(attacker);
        assertThat(result.board().pieceCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("blockedSlidingMoves")
    void blocksSlidingPieces(PieceType type, String from, String blocker, String to) {
        Board board = Board.empty()
                .withPiece(Square.fromAlgebraic(from), new Piece(Side.WHITE, type))
                .withPiece(Square.fromAlgebraic(blocker), new Piece(Side.BLACK, PieceType.PAWN));
        ChessPosition position = new ChessPosition(board, Side.WHITE);

        assertThatThrownBy(() -> position.apply(Move.between(from, to)))
                .isInstanceOf(IllegalMoveException.class);
    }

    @Test
    void allowsKnightToJumpOverPieces() {
        Board board = Board.empty()
                .withPiece(Square.fromAlgebraic("b1"), new Piece(Side.WHITE, PieceType.KNIGHT))
                .withPiece(Square.fromAlgebraic("b2"), new Piece(Side.WHITE, PieceType.PAWN))
                .withPiece(Square.fromAlgebraic("c2"), new Piece(Side.WHITE, PieceType.PAWN));

        ChessPosition result = new ChessPosition(board, Side.WHITE).apply(Move.between("b1", "c3"));

        assertThat(result.board().pieceAt(Square.fromAlgebraic("c3")))
                .contains(new Piece(Side.WHITE, PieceType.KNIGHT));
    }

    @Test
    void appliesPawnMovementAndCaptureRulesForBothSides() {
        Board whiteBoard = Board.empty()
                .withPiece(Square.fromAlgebraic("d2"), new Piece(Side.WHITE, PieceType.PAWN));
        Board blackBoard = Board.empty()
                .withPiece(Square.fromAlgebraic("e7"), new Piece(Side.BLACK, PieceType.PAWN))
                .withPiece(Square.fromAlgebraic("d6"), new Piece(Side.WHITE, PieceType.KNIGHT));

        ChessPosition whiteResult = new ChessPosition(whiteBoard, Side.WHITE).apply(Move.between("d2", "d4"));
        ChessPosition blackResult = new ChessPosition(blackBoard, Side.BLACK).apply(Move.between("e7", "d6"));

        assertThat(whiteResult.board().pieceAt(Square.fromAlgebraic("d4")))
                .contains(new Piece(Side.WHITE, PieceType.PAWN));
        assertThat(blackResult.board().pieceAt(Square.fromAlgebraic("d6")))
                .contains(new Piece(Side.BLACK, PieceType.PAWN));
    }

    @Test
    void rejectsBlockedPawnAndInvalidPawnCaptures() {
        Board board = Board.empty()
                .withPiece(Square.fromAlgebraic("e2"), new Piece(Side.WHITE, PieceType.PAWN))
                .withPiece(Square.fromAlgebraic("e3"), new Piece(Side.BLACK, PieceType.KNIGHT));
        ChessPosition position = new ChessPosition(board, Side.WHITE);

        assertThatThrownBy(() -> position.apply(Move.between("e2", "e3")))
                .isInstanceOf(IllegalMoveException.class);
        assertThatThrownBy(() -> position.apply(Move.between("e2", "e4")))
                .isInstanceOf(IllegalMoveException.class);
        assertThatThrownBy(() -> position.apply(Move.between("e2", "d3")))
                .isInstanceOf(IllegalMoveException.class);
    }

    @Test
    void rejectsOwnPieceCaptureAndPieceFromTheWrongSide() {
        Board board = Board.empty()
                .withPiece(Square.fromAlgebraic("a1"), new Piece(Side.WHITE, PieceType.ROOK))
                .withPiece(Square.fromAlgebraic("a2"), new Piece(Side.WHITE, PieceType.PAWN))
                .withPiece(Square.fromAlgebraic("h8"), new Piece(Side.BLACK, PieceType.ROOK));
        ChessPosition position = new ChessPosition(board, Side.WHITE);

        assertThatThrownBy(() -> position.apply(Move.between("a1", "a2")))
                .isInstanceOf(IllegalMoveException.class);
        assertThatThrownBy(() -> position.apply(Move.between("h8", "h7")))
                .isInstanceOf(IllegalMoveException.class);
    }

    @Test
    void listsTwentyBasicMovesFromInitialPosition() {
        assertThat(ChessPosition.initial().legalMoves()).hasSize(20);
    }

    private static ChessPosition positionWith(Piece piece, String square) {
        Board board = Board.empty().withPiece(Square.fromAlgebraic(square), piece);
        return new ChessPosition(board, piece.side());
    }

    private static Stream<Arguments> basicMoves() {
        return Stream.of(
                Arguments.of(PieceType.KING, "d4", "e5"),
                Arguments.of(PieceType.QUEEN, "d4", "h4"),
                Arguments.of(PieceType.ROOK, "d4", "d8"),
                Arguments.of(PieceType.BISHOP, "d4", "g7"),
                Arguments.of(PieceType.KNIGHT, "d4", "f5"),
                Arguments.of(PieceType.PAWN, "d2", "d3"));
    }

    private static Stream<Arguments> basicCaptures() {
        return Stream.of(
                Arguments.of(PieceType.KING, "d4", "e5"),
                Arguments.of(PieceType.QUEEN, "d4", "h4"),
                Arguments.of(PieceType.ROOK, "d4", "d8"),
                Arguments.of(PieceType.BISHOP, "d4", "g7"),
                Arguments.of(PieceType.KNIGHT, "d4", "f5"),
                Arguments.of(PieceType.PAWN, "d4", "e5"));
    }

    private static Stream<Arguments> blockedSlidingMoves() {
        return Stream.of(
                Arguments.of(PieceType.ROOK, "a1", "a3", "a8"),
                Arguments.of(PieceType.BISHOP, "a1", "c3", "h8"),
                Arguments.of(PieceType.QUEEN, "d4", "f6", "h8"));
    }
}
