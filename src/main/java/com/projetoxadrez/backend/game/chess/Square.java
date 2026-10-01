package com.projetoxadrez.backend.game.chess;

public record Square(int file, int rank) {

    private static final int BOARD_SIZE = 8;

    public Square {
        if (file < 0 || file >= BOARD_SIZE || rank < 0 || rank >= BOARD_SIZE) {
            throw new IllegalArgumentException("Square must be inside the board");
        }
    }

    public static Square fromAlgebraic(String value) {
        if (value == null || value.length() != 2) {
            throw new IllegalArgumentException("Square must use algebraic notation");
        }

        char file = Character.toLowerCase(value.charAt(0));
        char rank = value.charAt(1);
        if (file < 'a' || file > 'h' || rank < '1' || rank > '8') {
            throw new IllegalArgumentException("Square must be between a1 and h8");
        }
        return new Square(file - 'a', rank - '1');
    }

    public String toAlgebraic() {
        return "%c%c".formatted((char) ('a' + file), (char) ('1' + rank));
    }

    @Override
    public String toString() {
        return toAlgebraic();
    }
}
