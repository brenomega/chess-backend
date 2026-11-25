package com.projetoxadrez.backend.game.application;

public interface GameStatePublisher {

    default void publishAfterCommit(GameSnapshot snapshot) {
        publishAfterCommit(snapshot, null);
    }

    void publishAfterCommit(GameSnapshot snapshot, String excludedConnectionId);
}
