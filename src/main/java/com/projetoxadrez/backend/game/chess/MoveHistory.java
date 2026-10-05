package com.projetoxadrez.backend.game.chess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MoveHistory(List<String> uciMoves) {

    public MoveHistory {
        Objects.requireNonNull(uciMoves, "uciMoves must not be null");
        uciMoves = uciMoves.stream()
                .map(MoveHistory::validateUci)
                .toList();
    }

    public static MoveHistory empty() {
        return new MoveHistory(List.of());
    }

    public MoveHistory append(Move move) {
        Objects.requireNonNull(move, "move must not be null");
        List<String> updated = new ArrayList<>(uciMoves);
        updated.add(move.toUci());
        return new MoveHistory(updated);
    }

    public Optional<String> lastMoveUci() {
        return uciMoves.isEmpty() ? Optional.empty() : Optional.of(uciMoves.getLast());
    }

    private static String validateUci(String value) {
        Move move = Move.fromUci(value);
        if (!move.toUci().equals(value)) {
            throw new IllegalArgumentException("Move history must use canonical UCI notation");
        }
        return value;
    }
}
