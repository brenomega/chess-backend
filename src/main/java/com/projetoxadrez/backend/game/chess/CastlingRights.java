package com.projetoxadrez.backend.game.chess;

public record CastlingRights(
        boolean whiteKingSide,
        boolean whiteQueenSide,
        boolean blackKingSide,
        boolean blackQueenSide) {

    public static CastlingRights initial() {
        return new CastlingRights(true, true, true, true);
    }

    public static CastlingRights none() {
        return new CastlingRights(false, false, false, false);
    }

    public boolean allows(Side side, boolean kingSide) {
        return switch (side) {
            case WHITE -> kingSide ? whiteKingSide : whiteQueenSide;
            case BLACK -> kingSide ? blackKingSide : blackQueenSide;
        };
    }

    CastlingRights withoutSide(Side side) {
        return side == Side.WHITE
                ? new CastlingRights(false, false, blackKingSide, blackQueenSide)
                : new CastlingRights(whiteKingSide, whiteQueenSide, false, false);
    }

    CastlingRights withoutRookAt(Square square) {
        return switch (square.toAlgebraic()) {
            case "h1" -> new CastlingRights(false, whiteQueenSide, blackKingSide, blackQueenSide);
            case "a1" -> new CastlingRights(whiteKingSide, false, blackKingSide, blackQueenSide);
            case "h8" -> new CastlingRights(whiteKingSide, whiteQueenSide, false, blackQueenSide);
            case "a8" -> new CastlingRights(whiteKingSide, whiteQueenSide, blackKingSide, false);
            default -> this;
        };
    }
}
