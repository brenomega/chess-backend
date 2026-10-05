package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LegalMoveServiceTest {

    private final LegalMoveService service = new LegalMoveService();

    @Test
    void detectsCheckOnlyWhenAttackReachesTheKing() {
        Board exposedKing = board(
                "e1", Side.WHITE, PieceType.KING,
                "a8", Side.BLACK, PieceType.KING,
                "e8", Side.BLACK, PieceType.ROOK);
        Board protectedKing = exposedKing.withPiece(
                Square.fromAlgebraic("e4"),
                new Piece(Side.WHITE, PieceType.BISHOP));

        assertThat(service.isInCheck(position(exposedKing, Side.WHITE), Side.WHITE)).isTrue();
        assertThat(service.isInCheck(position(protectedKing, Side.WHITE), Side.WHITE)).isFalse();
        assertThat(service.isInCheck(position(exposedKing, Side.WHITE), Side.BLACK)).isFalse();
    }

    @Test
    void detectsPawnKnightAndKingAttacksAtBoardEdges() {
        Board board = board(
                "a1", Side.WHITE, PieceType.KING,
                "h8", Side.BLACK, PieceType.KING,
                "b2", Side.BLACK, PieceType.PAWN,
                "f7", Side.WHITE, PieceType.KNIGHT);
        ChessPosition position = position(board, Side.WHITE);

        assertThat(service.isInCheck(position, Side.WHITE)).isTrue();
        assertThat(service.isInCheck(position, Side.BLACK)).isTrue();

        Board adjacentKings = board(
                "a1", Side.WHITE, PieceType.KING,
                "b2", Side.BLACK, PieceType.KING);
        assertThat(service.isInCheck(position(adjacentKings, Side.WHITE), Side.WHITE)).isTrue();
        assertThat(service.isInCheck(position(adjacentKings, Side.BLACK), Side.BLACK)).isTrue();
    }

    @Test
    void rejectsMoveThatExposesOwnKing() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "e2", Side.WHITE, PieceType.ROOK,
                "a8", Side.BLACK, PieceType.KING,
                "e8", Side.BLACK, PieceType.ROOK);
        ChessPosition position = position(board, Side.WHITE);
        Move exposesKing = Move.between("e2", "f2");

        assertThat(service.pseudoLegalMoves(position)).contains(exposesKing);
        assertThat(position.legalMoves()).doesNotContain(exposesKing);
        assertThatThrownBy(() -> position.apply(exposesKing))
                .isInstanceOf(IllegalMoveException.class);
        assertThat(position.board()).isEqualTo(board);
        assertThat(position.sideToMove()).isEqualTo(Side.WHITE);
    }

    @Test
    void permitsMoveThatEscapesCheckAndRejectsKingMoveIntoAttack() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "a8", Side.BLACK, PieceType.KING,
                "e8", Side.BLACK, PieceType.ROOK);
        ChessPosition position = position(board, Side.WHITE);

        ChessPosition escaped = position.apply(Move.between("e1", "d1"));

        assertThat(escaped.isInCheck(Side.WHITE)).isFalse();
        assertThatThrownBy(() -> position.apply(Move.between("e1", "e2")))
                .isInstanceOf(IllegalMoveException.class);
    }

    @Test
    void neverGeneratesCaptureOfTheOpposingKing() {
        Board board = board(
                "a1", Side.WHITE, PieceType.KING,
                "e7", Side.WHITE, PieceType.ROOK,
                "e8", Side.BLACK, PieceType.KING);
        ChessPosition position = position(board, Side.WHITE);
        Move kingCapture = Move.between("e7", "e8");

        assertThat(service.pseudoLegalMoves(position)).doesNotContain(kingCapture);
        assertThat(position.legalMoves()).doesNotContain(kingCapture);
    }

    @Test
    void generatesKnownLegalMoveCountsFromInitialPosition() {
        ChessPosition initial = ChessPosition.initial();

        assertThat(perft(initial, 1)).isEqualTo(20);
        assertThat(perft(initial, 2)).isEqualTo(400);
        assertThat(perft(initial, 3)).isEqualTo(8_902);
    }

    private static long perft(ChessPosition position, int depth) {
        if (depth == 0) {
            return 1;
        }
        return position.legalMoves().stream()
                .mapToLong(move -> perft(position.apply(move), depth - 1))
                .sum();
    }

    private static ChessPosition position(Board board, Side side) {
        return new ChessPosition(board, side, CastlingRights.none(), null);
    }

    private static Board board(Object... pieces) {
        Board board = Board.empty();
        for (int index = 0; index < pieces.length; index += 3) {
            board = board.withPiece(
                    Square.fromAlgebraic((String) pieces[index]),
                    new Piece((Side) pieces[index + 1], (PieceType) pieces[index + 2]));
        }
        return board;
    }
}
