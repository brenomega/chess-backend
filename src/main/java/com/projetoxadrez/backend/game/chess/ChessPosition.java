package com.projetoxadrez.backend.game.chess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ChessPosition(Board board, Side sideToMove) {

    public ChessPosition {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(sideToMove, "sideToMove must not be null");
    }

    public static ChessPosition initial() {
        return new ChessPosition(Board.initial(), Side.WHITE);
    }

    public List<Move> legalMoves() {
        List<Move> moves = new ArrayList<>();
        for (var entry : board.pieces().entrySet()) {
            if (entry.getValue().side() != sideToMove) {
                continue;
            }
            for (int file = 0; file < 8; file++) {
                for (int rank = 0; rank < 8; rank++) {
                    Square destination = new Square(file, rank);
                    if (!entry.getKey().equals(destination)) {
                        Move move = new Move(entry.getKey(), destination);
                        if (BasicMoveRules.isLegal(board, move, sideToMove)) {
                            moves.add(move);
                        }
                    }
                }
            }
        }
        return List.copyOf(moves);
    }

    public ChessPosition apply(Move move) {
        Objects.requireNonNull(move, "move must not be null");
        if (!BasicMoveRules.isLegal(board, move, sideToMove)) {
            throw new IllegalMoveException("Illegal move: " + move.toUci());
        }
        return new ChessPosition(board.apply(move), sideToMove.opposite());
    }
}
