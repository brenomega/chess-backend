package com.projetoxadrez.backend.game.chess;

public enum ChessResult {
    WHITE_WIN_BY_CHECKMATE("WHITE_WIN", "CHECKMATE"),
    BLACK_WIN_BY_CHECKMATE("BLACK_WIN", "CHECKMATE"),
    DRAW_BY_STALEMATE("DRAW", "STALEMATE"),
    DRAW_BY_INSUFFICIENT_MATERIAL("DRAW", "INSUFFICIENT_MATERIAL");

    private final String outcome;
    private final String reason;

    ChessResult(String outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    public String outcome() {
        return outcome;
    }

    public String reason() {
        return reason;
    }
}
