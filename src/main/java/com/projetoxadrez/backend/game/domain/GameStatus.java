package com.projetoxadrez.backend.game.domain;

public enum GameStatus {
    WAITING,
    ACTIVE,
    FINISHED,
    ABANDONED;

    public boolean canTransitionTo(GameStatus target) {
        return switch (this) {
            case WAITING -> target == ACTIVE || target == ABANDONED;
            case ACTIVE -> target == FINISHED || target == ABANDONED;
            case FINISHED, ABANDONED -> false;
        };
    }
}
