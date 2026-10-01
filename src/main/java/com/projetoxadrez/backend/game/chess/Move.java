package com.projetoxadrez.backend.game.chess;

import java.util.Objects;

public record Move(Square from, Square to) {

    public Move {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.equals(to)) {
            throw new IllegalArgumentException("Move must change the square");
        }
    }

    public static Move between(String from, String to) {
        return new Move(Square.fromAlgebraic(from), Square.fromAlgebraic(to));
    }

    public String toUci() {
        return from.toAlgebraic() + to.toAlgebraic();
    }
}
