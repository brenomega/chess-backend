package com.projetoxadrez.backend.game.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterCommitGameStatePublisherTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOnlyAfterCommit() {
        TrackingSubscriptions subscriptions = new TrackingSubscriptions();
        AfterCommitGameStatePublisher publisher = new AfterCommitGameStatePublisher(subscriptions);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(null);

        assertThat(subscriptions.publications).isZero();
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(subscriptions.publications).isEqualTo(1);
    }

    @Test
    void doesNotPublishWhenTransactionRollsBack() {
        TrackingSubscriptions subscriptions = new TrackingSubscriptions();
        AfterCommitGameStatePublisher publisher = new AfterCommitGameStatePublisher(subscriptions);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(null);
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(subscriptions.publications).isZero();
    }

    private static final class TrackingSubscriptions extends GameSubscriptionRegistry {

        private int publications;

        private TrackingSubscriptions() {
            super(new ObjectMapper());
        }

        @Override
        public void publish(GameSnapshot snapshot) {
            publications++;
        }
    }
}
