package com.projetoxadrez.backend.game.chess;

final class BasicMoveRules {

    private BasicMoveRules() {
    }

    static boolean isLegal(Board board, Move move, Side sideToMove) {
        Piece piece = board.pieceAt(move.from()).orElse(null);
        if (piece == null || piece.side() != sideToMove) {
            return false;
        }

        Piece target = board.pieceAt(move.to()).orElse(null);
        if (target != null && target.side() == piece.side()) {
            return false;
        }

        int fileDistance = move.to().file() - move.from().file();
        int rankDistance = move.to().rank() - move.from().rank();
        return switch (piece.type()) {
            case PAWN -> isLegalPawnMove(board, move, piece.side(), target, fileDistance, rankDistance);
            case KNIGHT -> isKnightMove(fileDistance, rankDistance);
            case BISHOP -> isDiagonal(fileDistance, rankDistance) && pathIsClear(board, move);
            case ROOK -> isStraight(fileDistance, rankDistance) && pathIsClear(board, move);
            case QUEEN -> (isStraight(fileDistance, rankDistance) || isDiagonal(fileDistance, rankDistance))
                    && pathIsClear(board, move);
            case KING -> Math.max(Math.abs(fileDistance), Math.abs(rankDistance)) == 1;
        };
    }

    private static boolean isLegalPawnMove(
            Board board,
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
            return target == null && board.pieceAt(intermediate).isEmpty();
        }
        return Math.abs(fileDistance) == 1 && rankDistance == direction && target != null;
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
