package com.projetoxadrez.backend.game.chess;

public final class IllegalMoveException extends IllegalArgumentException {

    public IllegalMoveException(String message) {
        super(message);
    }
}
