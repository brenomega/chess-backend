package com.projetoxadrez.backend.game.application;

public interface GameStatePublisher {

    void publishAfterCommit(GameSnapshot snapshot);
}
