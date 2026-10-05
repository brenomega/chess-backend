package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpecialMoveRulesTest {

    @Test
    void castlesKingSideAndMovesTheRook() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "h1", Side.WHITE, PieceType.ROOK,
                "e8", Side.BLACK, PieceType.KING);
        CastlingRights rights = new CastlingRights(true, false, false, false);
        ChessPosition position = new ChessPosition(board, Side.WHITE, rights, null);

        ChessPosition result = position.apply(Move.between("e1", "g1"));

        assertThat(result.board().pieceAt(Square.fromAlgebraic("g1")))
                .contains(new Piece(Side.WHITE, PieceType.KING));
        assertThat(result.board().pieceAt(Square.fromAlgebraic("f1")))
                .contains(new Piece(Side.WHITE, PieceType.ROOK));
        assertThat(result.board().pieceAt(Square.fromAlgebraic("e1"))).isEmpty();
        assertThat(result.board().pieceAt(Square.fromAlgebraic("h1"))).isEmpty();
        assertThat(result.castlingRights().allows(Side.WHITE, true)).isFalse();
    }

    @Test
    void castlesBlackQueenSide() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "e8", Side.BLACK, PieceType.KING,
                "a8", Side.BLACK, PieceType.ROOK);
        CastlingRights rights = new CastlingRights(false, false, false, true);

        ChessPosition result = new ChessPosition(board, Side.BLACK, rights, null)
                .apply(Move.between("e8", "c8"));

        assertThat(result.board().pieceAt(Square.fromAlgebraic("c8")))
                .contains(new Piece(Side.BLACK, PieceType.KING));
        assertThat(result.board().pieceAt(Square.fromAlgebraic("d8")))
                .contains(new Piece(Side.BLACK, PieceType.ROOK));
        assertThat(result.castlingRights().allows(Side.BLACK, false)).isFalse();
    }

    @Test
    void rejectsCastlingWithoutRightsRookOrClearPath() {
        Move castle = Move.between("e1", "c1");
        Board clearBoard = board(
                "e1", Side.WHITE, PieceType.KING,
                "a1", Side.WHITE, PieceType.ROOK,
                "e8", Side.BLACK, PieceType.KING);
        ChessPosition withoutRights = new ChessPosition(clearBoard, Side.WHITE, CastlingRights.none(), null);
        ChessPosition withoutRook = new ChessPosition(
                clearBoard.withoutPiece(Square.fromAlgebraic("a1")),
                Side.WHITE,
                new CastlingRights(false, true, false, false),
                null);
        ChessPosition blocked = new ChessPosition(
                clearBoard.withPiece(Square.fromAlgebraic("b1"), new Piece(Side.WHITE, PieceType.KNIGHT)),
                Side.WHITE,
                new CastlingRights(false, true, false, false),
                null);

        assertIllegal(withoutRights, castle);
        assertIllegal(withoutRook, castle);
        assertIllegal(blocked, castle);
    }

    @Test
    void rejectsCastlingOutOfThroughOrIntoCheck() {
        CastlingRights rights = new CastlingRights(true, false, false, false);
        Board base = board(
                "e1", Side.WHITE, PieceType.KING,
                "h1", Side.WHITE, PieceType.ROOK,
                "a8", Side.BLACK, PieceType.KING);

        ChessPosition outOfCheck = new ChessPosition(
                base.withPiece(Square.fromAlgebraic("e8"), new Piece(Side.BLACK, PieceType.ROOK)),
                Side.WHITE,
                rights,
                null);
        ChessPosition throughCheck = new ChessPosition(
                base.withPiece(Square.fromAlgebraic("f8"), new Piece(Side.BLACK, PieceType.ROOK)),
                Side.WHITE,
                rights,
                null);
        ChessPosition intoCheck = new ChessPosition(
                base.withPiece(Square.fromAlgebraic("g8"), new Piece(Side.BLACK, PieceType.ROOK)),
                Side.WHITE,
                rights,
                null);

        assertIllegal(outOfCheck, Move.between("e1", "g1"));
        assertIllegal(throughCheck, Move.between("e1", "g1"));
        assertIllegal(intoCheck, Move.between("e1", "g1"));
    }

    @Test
    void revokesCastlingRightsWhenRookMovesOrIsCaptured() {
        Board rookMoveBoard = board(
                "e1", Side.WHITE, PieceType.KING,
                "h1", Side.WHITE, PieceType.ROOK,
                "e8", Side.BLACK, PieceType.KING);
        ChessPosition afterRookMove = new ChessPosition(
                        rookMoveBoard,
                        Side.WHITE,
                        new CastlingRights(true, false, false, false),
                        null)
                .apply(Move.between("h1", "h2"));

        Board captureBoard = board(
                "e1", Side.WHITE, PieceType.KING,
                "a1", Side.WHITE, PieceType.ROOK,
                "e8", Side.BLACK, PieceType.KING,
                "a8", Side.BLACK, PieceType.ROOK);
        ChessPosition afterCapture = new ChessPosition(
                        captureBoard,
                        Side.BLACK,
                        new CastlingRights(false, true, false, false),
                        null)
                .apply(Move.between("a8", "a1"));

        assertThat(afterRookMove.castlingRights().allows(Side.WHITE, true)).isFalse();
        assertThat(afterCapture.castlingRights().allows(Side.WHITE, false)).isFalse();
    }

    @Test
    void allowsEnPassantOnlyOnTheImmediateReply() {
        ChessPosition position = ChessPosition.initial()
                .apply(Move.between("e2", "e4"))
                .apply(Move.between("a7", "a6"))
                .apply(Move.between("e4", "e5"))
                .apply(Move.between("d7", "d5"));

        assertThat(position.enPassantTarget()).isEqualTo(Square.fromAlgebraic("d6"));

        ChessPosition captured = position.apply(Move.between("e5", "d6"));
        assertThat(captured.board().pieceAt(Square.fromAlgebraic("d5"))).isEmpty();
        assertThat(captured.board().pieceAt(Square.fromAlgebraic("d6")))
                .contains(new Piece(Side.WHITE, PieceType.PAWN));
        assertThat(captured.enPassantTarget()).isNull();

        ChessPosition expired = position
                .apply(Move.between("g1", "f3"))
                .apply(Move.between("a6", "a5"));
        assertIllegal(expired, Move.between("e5", "d6"));
    }

    @Test
    void rejectsEnPassantThatWouldExposeOwnKing() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "e5", Side.WHITE, PieceType.PAWN,
                "a8", Side.BLACK, PieceType.KING,
                "e8", Side.BLACK, PieceType.ROOK,
                "d5", Side.BLACK, PieceType.PAWN);
        ChessPosition position = new ChessPosition(
                board,
                Side.WHITE,
                CastlingRights.none(),
                Square.fromAlgebraic("d6"));

        assertIllegal(position, Move.between("e5", "d6"));
    }

    @Test
    void requiresPromotionAndGeneratesEveryPromotionChoice() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "a7", Side.WHITE, PieceType.PAWN,
                "e8", Side.BLACK, PieceType.KING);
        ChessPosition position = new ChessPosition(board, Side.WHITE, CastlingRights.none(), null);

        assertIllegal(position, Move.between("a7", "a8"));
        assertThat(position.legalMoves())
                .contains(
                        Move.between("a7", "a8", PieceType.QUEEN),
                        Move.between("a7", "a8", PieceType.ROOK),
                        Move.between("a7", "a8", PieceType.BISHOP),
                        Move.between("a7", "a8", PieceType.KNIGHT));

        ChessPosition promoted = position.apply(Move.between("a7", "a8", PieceType.KNIGHT));
        assertThat(promoted.board().pieceAt(Square.fromAlgebraic("a8")))
                .contains(new Piece(Side.WHITE, PieceType.KNIGHT));
        assertThat(Move.between("a7", "a8", PieceType.KNIGHT).toUci()).isEqualTo("a7a8n");
    }

    @Test
    void rejectsPromotionAwayFromLastRankAndInvalidPromotionPieces() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "a6", Side.WHITE, PieceType.PAWN,
                "e8", Side.BLACK, PieceType.KING);
        ChessPosition position = new ChessPosition(board, Side.WHITE, CastlingRights.none(), null);

        assertIllegal(position, Move.between("a6", "a7", PieceType.QUEEN));
        assertThatThrownBy(() -> Move.between("a7", "a8", PieceType.KING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Move.between("a7", "a8", PieceType.PAWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promotesBlackPawnWhileCapturing() {
        Board board = board(
                "e1", Side.WHITE, PieceType.KING,
                "a1", Side.WHITE, PieceType.ROOK,
                "b2", Side.BLACK, PieceType.PAWN,
                "e8", Side.BLACK, PieceType.KING);
        ChessPosition position = new ChessPosition(board, Side.BLACK, CastlingRights.none(), null);

        ChessPosition promoted = position.apply(Move.between("b2", "a1", PieceType.QUEEN));

        assertThat(promoted.board().pieceAt(Square.fromAlgebraic("a1")))
                .contains(new Piece(Side.BLACK, PieceType.QUEEN));
        assertThat(promoted.board().pieceAt(Square.fromAlgebraic("b2"))).isEmpty();
    }

    private static void assertIllegal(ChessPosition position, Move move) {
        assertThatThrownBy(() -> position.apply(move)).isInstanceOf(IllegalMoveException.class);
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
