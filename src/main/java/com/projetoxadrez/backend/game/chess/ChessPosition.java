package com.projetoxadrez.backend.game.chess;

import java.util.List;
import java.util.Objects;

public record ChessPosition(
        Board board,
        Side sideToMove,
        CastlingRights castlingRights,
        Square enPassantTarget) {

    private static final LegalMoveService LEGAL_MOVES = new LegalMoveService();

    public ChessPosition(Board board, Side sideToMove) {
        this(board, sideToMove, CastlingRights.none(), null);
    }

    public ChessPosition {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(sideToMove, "sideToMove must not be null");
        Objects.requireNonNull(castlingRights, "castlingRights must not be null");
    }

    public static ChessPosition initial() {
        return new ChessPosition(Board.initial(), Side.WHITE, CastlingRights.initial(), null);
    }

    public List<Move> legalMoves() {
        return LEGAL_MOVES.legalMoves(this);
    }

    public boolean isInCheck(Side side) {
        return LEGAL_MOVES.isInCheck(this, Objects.requireNonNull(side, "side must not be null"));
    }

    public ChessPosition apply(Move move) {
        Objects.requireNonNull(move, "move must not be null");
        if (!LEGAL_MOVES.isLegal(this, move)) {
            throw new IllegalMoveException("Illegal move: " + move.toUci());
        }
        return applyUnchecked(move);
    }

    ChessPosition applyUnchecked(Move move) {
        Piece movingPiece = board.pieceAt(move.from()).orElseThrow();
        Piece capturedPiece = board.pieceAt(move.to()).orElse(null);
        Board updated = board.move(move.from(), move.to());

        if (isEnPassant(move, movingPiece, capturedPiece)) {
            updated = updated.withoutPiece(new Square(move.to().file(), move.from().rank()));
        }
        if (isCastling(move, movingPiece)) {
            int rank = move.from().rank();
            boolean kingSide = move.to().file() == 6;
            updated = updated.move(new Square(kingSide ? 7 : 0, rank), new Square(kingSide ? 5 : 3, rank));
        }
        if (move.promotion() != null) {
            updated = updated.replace(move.to(), new Piece(movingPiece.side(), move.promotion()));
        }

        CastlingRights updatedRights = updateCastlingRights(move, movingPiece, capturedPiece);
        Square nextEnPassantTarget = enPassantTarget(move, movingPiece);
        return new ChessPosition(updated, sideToMove.opposite(), updatedRights, nextEnPassantTarget);
    }

    private CastlingRights updateCastlingRights(Move move, Piece movingPiece, Piece capturedPiece) {
        CastlingRights updated = castlingRights;
        if (movingPiece.type() == PieceType.KING) {
            updated = updated.withoutSide(movingPiece.side());
        }
        if (movingPiece.type() == PieceType.ROOK) {
            updated = updated.withoutRookAt(move.from());
        }
        if (capturedPiece != null && capturedPiece.type() == PieceType.ROOK) {
            updated = updated.withoutRookAt(move.to());
        }
        return updated;
    }

    private static Square enPassantTarget(Move move, Piece piece) {
        if (piece.type() != PieceType.PAWN || Math.abs(move.to().rank() - move.from().rank()) != 2) {
            return null;
        }
        return new Square(move.from().file(), (move.from().rank() + move.to().rank()) / 2);
    }

    private boolean isEnPassant(Move move, Piece piece, Piece capturedPiece) {
        return piece.type() == PieceType.PAWN
                && capturedPiece == null
                && move.to().equals(enPassantTarget)
                && move.from().file() != move.to().file();
    }

    private static boolean isCastling(Move move, Piece piece) {
        return piece.type() == PieceType.KING && Math.abs(move.to().file() - move.from().file()) == 2;
    }
}
