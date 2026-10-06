package com.projetoxadrez.backend.game.websocket;

import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.GameStatePublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AfterCommitGameStatePublisher implements GameStatePublisher {

    private final GameSubscriptionRegistry subscriptions;

    public AfterCommitGameStatePublisher(GameSubscriptionRegistry subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void publishAfterCommit(GameSnapshot snapshot) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Game state publication requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                subscriptions.publish(snapshot);
            }
        });
    }
}
