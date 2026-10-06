package com.projetoxadrez.backend.game.chess;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TerminalStateService {

    public Optional<ChessResult> result(ChessPosition position) {
        Objects.requireNonNull(position, "position must not be null");
        if (position.legalMoves().isEmpty()) {
            if (!position.isInCheck(position.sideToMove())) {
                return Optional.of(ChessResult.DRAW_BY_STALEMATE);
            }
            return Optional.of(position.sideToMove() == Side.WHITE
                    ? ChessResult.BLACK_WIN_BY_CHECKMATE
                    : ChessResult.WHITE_WIN_BY_CHECKMATE);
        }
        if (hasInsufficientMaterial(position.board())) {
            return Optional.of(ChessResult.DRAW_BY_INSUFFICIENT_MATERIAL);
        }
        return Optional.empty();
    }

    boolean hasInsufficientMaterial(Board board) {
        List<Map.Entry<Square, Piece>> nonKings = board.pieces().entrySet().stream()
                .filter(entry -> entry.getValue().type() != PieceType.KING)
                .toList();
        if (nonKings.isEmpty()) {
            return true;
        }
        if (nonKings.size() == 1) {
            PieceType type = nonKings.getFirst().getValue().type();
            return type == PieceType.BISHOP || type == PieceType.KNIGHT;
        }
        if (nonKings.stream().anyMatch(entry -> entry.getValue().type() != PieceType.BISHOP)) {
            return false;
        }
        int bishopSquareColor = squareColor(nonKings.getFirst().getKey());
        return nonKings.stream().allMatch(entry -> squareColor(entry.getKey()) == bishopSquareColor);
    }

    private static int squareColor(Square square) {
        return (square.file() + square.rank()) % 2;
    }
}
