package com.projetoxadrez.backend.game.chess;

import java.util.Objects;

public record Piece(Side side, PieceType type) {

    public Piece {
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }
}
