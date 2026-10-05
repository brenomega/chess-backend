package com.projetoxadrez.backend.game.chess;

import java.util.ArrayList;
import java.util.List;

public final class LegalMoveService {

    private static final List<PieceType> PROMOTION_TYPES = List.of(
            PieceType.QUEEN,
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.KNIGHT);

    public List<Move> pseudoLegalMoves(ChessPosition position) {
        List<Move> moves = new ArrayList<>();
        for (var entry : position.board().pieces().entrySet()) {
            if (entry.getValue().side() != position.sideToMove()) {
                continue;
            }
            for (int file = 0; file < 8; file++) {
                for (int rank = 0; rank < 8; rank++) {
                    Square destination = new Square(file, rank);
                    if (!entry.getKey().equals(destination)) {
                        addPseudoLegalMoves(position, entry.getKey(), destination, entry.getValue(), moves);
                    }
                }
            }
        }
        return List.copyOf(moves);
    }

    public List<Move> legalMoves(ChessPosition position) {
        return pseudoLegalMoves(position).stream()
                .filter(move -> leavesKingSafe(position, move))
                .toList();
    }

    public boolean isLegal(ChessPosition position, Move move) {
        return isPseudoLegal(position, move) && leavesKingSafe(position, move);
    }

    public boolean isInCheck(ChessPosition position, Side side) {
        return position.board().pieces().entrySet().stream()
                .filter(entry -> entry.getValue().equals(new Piece(side, PieceType.KING)))
                .map(entry -> isSquareAttacked(position.board(), entry.getKey(), side.opposite()))
                .findFirst()
                .orElse(false);
    }

    private void addPseudoLegalMoves(
            ChessPosition position,
            Square from,
            Square to,
            Piece piece,
            List<Move> moves) {
        if (piece.type() == PieceType.PAWN && isPromotionRank(to, piece.side())) {
            for (PieceType promotion : PROMOTION_TYPES) {
                Move move = new Move(from, to, promotion);
                if (isPseudoLegal(position, move)) {
                    moves.add(move);
                }
            }
            return;
        }
        Move move = new Move(from, to);
        if (isPseudoLegal(position, move)) {
            moves.add(move);
        }
    }

    private boolean isPseudoLegal(ChessPosition position, Move move) {
        Piece piece = position.board().pieceAt(move.from()).orElse(null);
        if (piece == null || piece.side() != position.sideToMove() || !hasValidPromotion(move, piece)) {
            return false;
        }

        Piece target = position.board().pieceAt(move.to()).orElse(null);
        if (target != null && (target.side() == piece.side() || target.type() == PieceType.KING)) {
            return false;
        }

        int fileDistance = move.to().file() - move.from().file();
        int rankDistance = move.to().rank() - move.from().rank();
        return switch (piece.type()) {
            case PAWN -> isPseudoLegalPawnMove(position, move, piece.side(), target, fileDistance, rankDistance);
            case KNIGHT -> isKnightMove(fileDistance, rankDistance);
            case BISHOP -> isDiagonal(fileDistance, rankDistance) && pathIsClear(position.board(), move);
            case ROOK -> isStraight(fileDistance, rankDistance) && pathIsClear(position.board(), move);
            case QUEEN -> (isStraight(fileDistance, rankDistance) || isDiagonal(fileDistance, rankDistance))
                    && pathIsClear(position.board(), move);
            case KING -> isKingMove(position, move, piece.side(), fileDistance, rankDistance);
        };
    }

    private boolean leavesKingSafe(ChessPosition position, Move move) {
        Side movingSide = position.sideToMove();
        return !isInCheck(position.applyUnchecked(move), movingSide);
    }

    private boolean isKingMove(
            ChessPosition position,
            Move move,
            Side side,
            int fileDistance,
            int rankDistance) {
        if (Math.max(Math.abs(fileDistance), Math.abs(rankDistance)) == 1) {
            return true;
        }
        return rankDistance == 0 && Math.abs(fileDistance) == 2 && canCastle(position, move, side);
    }

    private boolean canCastle(ChessPosition position, Move move, Side side) {
        int rank = side == Side.WHITE ? 0 : 7;
        boolean kingSide = move.to().file() == 6;
        if (!move.from().equals(new Square(4, rank))
                || move.to().rank() != rank
                || move.to().file() != (kingSide ? 6 : 2)
                || !position.castlingRights().allows(side, kingSide)) {
            return false;
        }

        Board board = position.board();
        Square rookSquare = new Square(kingSide ? 7 : 0, rank);
        if (!board.pieceAt(rookSquare).filter(new Piece(side, PieceType.ROOK)::equals).isPresent()) {
            return false;
        }

        int[] emptyFiles = kingSide ? new int[] {5, 6} : new int[] {1, 2, 3};
        for (int file : emptyFiles) {
            if (board.pieceAt(new Square(file, rank)).isPresent()) {
                return false;
            }
        }

        Side attacker = side.opposite();
        return !isSquareAttacked(board, new Square(4, rank), attacker)
                && !isSquareAttacked(board, new Square(kingSide ? 5 : 3, rank), attacker)
                && !isSquareAttacked(board, new Square(kingSide ? 6 : 2, rank), attacker);
    }

    private boolean isPseudoLegalPawnMove(
            ChessPosition position,
            Move move,
            Side side,
            Piece target,
            int fileDistance,
            int rankDistance) {
        int direction = side == Side.WHITE ? 1 : -1;
        int initialRank = side == Side.WHITE ? 1 : 6;
        if (fileDistance == 0 && rankDistance == direction) {
            return target == null;
        }
        if (fileDistance == 0 && rankDistance == 2 * direction && move.from().rank() == initialRank) {
            Square intermediate = new Square(move.from().file(), move.from().rank() + direction);
            return target == null && position.board().pieceAt(intermediate).isEmpty();
        }
        if (Math.abs(fileDistance) != 1 || rankDistance != direction) {
            return false;
        }
        if (target != null) {
            return true;
        }
        if (!move.to().equals(position.enPassantTarget())) {
            return false;
        }
        Square capturedPawn = new Square(move.to().file(), move.from().rank());
        return position.board().pieceAt(capturedPawn)
                .filter(new Piece(side.opposite(), PieceType.PAWN)::equals)
                .isPresent();
    }

    private boolean isSquareAttacked(Board board, Square square, Side attackingSide) {
        for (var entry : board.pieces().entrySet()) {
            Piece piece = entry.getValue();
            if (piece.side() == attackingSide && attacks(board, entry.getKey(), square, piece)) {
                return true;
            }
        }
        return false;
    }

    private boolean attacks(Board board, Square from, Square to, Piece piece) {
        int fileDistance = to.file() - from.file();
        int rankDistance = to.rank() - from.rank();
        return switch (piece.type()) {
            case PAWN -> Math.abs(fileDistance) == 1
                    && rankDistance == (piece.side() == Side.WHITE ? 1 : -1);
            case KNIGHT -> isKnightMove(fileDistance, rankDistance);
            case BISHOP -> isDiagonal(fileDistance, rankDistance) && pathIsClear(board, new Move(from, to));
            case ROOK -> isStraight(fileDistance, rankDistance) && pathIsClear(board, new Move(from, to));
            case QUEEN -> (isStraight(fileDistance, rankDistance) || isDiagonal(fileDistance, rankDistance))
                    && pathIsClear(board, new Move(from, to));
            case KING -> Math.max(Math.abs(fileDistance), Math.abs(rankDistance)) == 1;
        };
    }

    private static boolean hasValidPromotion(Move move, Piece piece) {
        boolean reachesPromotionRank = piece.type() == PieceType.PAWN && isPromotionRank(move.to(), piece.side());
        return reachesPromotionRank == (move.promotion() != null);
    }

    private static boolean isPromotionRank(Square square, Side side) {
        return square.rank() == (side == Side.WHITE ? 7 : 0);
    }

    private static boolean isKnightMove(int fileDistance, int rankDistance) {
        int absoluteFile = Math.abs(fileDistance);
        int absoluteRank = Math.abs(rankDistance);
        return absoluteFile == 1 && absoluteRank == 2 || absoluteFile == 2 && absoluteRank == 1;
    }

    private static boolean isDiagonal(int fileDistance, int rankDistance) {
        return Math.abs(fileDistance) == Math.abs(rankDistance);
    }

    private static boolean isStraight(int fileDistance, int rankDistance) {
        return fileDistance == 0 || rankDistance == 0;
    }

    private static boolean pathIsClear(Board board, Move move) {
        int fileStep = Integer.signum(move.to().file() - move.from().file());
        int rankStep = Integer.signum(move.to().rank() - move.from().rank());
        int file = move.from().file() + fileStep;
        int rank = move.from().rank() + rankStep;
        while (file != move.to().file() || rank != move.to().rank()) {
            if (board.pieceAt(new Square(file, rank)).isPresent()) {
                return false;
            }
            file += fileStep;
            rank += rankStep;
        }
        return true;
    }
}
