package com.projetoxadrez.backend.game.chess;

import java.util.Objects;

public record Move(Square from, Square to, PieceType promotion) {

    public Move(Square from, Square to) {
        this(from, to, null);
    }

    public Move {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.equals(to)) {
            throw new IllegalArgumentException("Move must change the square");
        }
        if (promotion == PieceType.KING || promotion == PieceType.PAWN) {
            throw new IllegalArgumentException("Promotion piece must be a queen, rook, bishop or knight");
        }
    }

    public static Move between(String from, String to) {
        return new Move(Square.fromAlgebraic(from), Square.fromAlgebraic(to));
    }

    public static Move between(String from, String to, PieceType promotion) {
        return new Move(Square.fromAlgebraic(from), Square.fromAlgebraic(to), promotion);
    }

    public static Move fromUci(String value) {
        if (value == null || !value.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            throw new IllegalArgumentException("Move must use UCI notation");
        }

        PieceType promotion = value.length() == 5
                ? switch (value.charAt(4)) {
                    case 'q' -> PieceType.QUEEN;
                    case 'r' -> PieceType.ROOK;
                    case 'b' -> PieceType.BISHOP;
                    case 'n' -> PieceType.KNIGHT;
                    default -> throw new IllegalArgumentException("Move must use UCI notation");
                }
                : null;
        return new Move(
                Square.fromAlgebraic(value.substring(0, 2)),
                Square.fromAlgebraic(value.substring(2, 4)),
                promotion);
    }

    public String toUci() {
        String suffix = switch (promotion) {
            case QUEEN -> "q";
            case ROOK -> "r";
            case BISHOP -> "b";
            case KNIGHT -> "n";
            case null -> "";
            default -> throw new IllegalStateException("Invalid promotion piece");
        };
        return from.toAlgebraic() + to.toAlgebraic() + suffix;
    }
}
