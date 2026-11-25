package com.projetoxadrez.backend.game.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterCommitGameStatePublisherTest {

    private final GameSnapshot snapshot = new GameSnapshot(
            UUID.randomUUID(), GameStatus.ACTIVE, GameVisibility.PUBLIC, null, 2, List.of(),
            new GameSnapshot.Position(ChessPosition.initial().toFen(), Side.WHITE, null),
            new GameSnapshot.Clock(0, 0, Side.WHITE), null, null);

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

        publisher.publishAfterCommit(snapshot);

        assertThat(subscriptions.publications).isZero();
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(subscriptions.publications).isEqualTo(1);
    }

    @Test
    void rollbackDoesNotPublishOrBlockTheNextTransition() {
        TrackingSubscriptions subscriptions = new TrackingSubscriptions();
        AfterCommitGameStatePublisher publisher = new AfterCommitGameStatePublisher(subscriptions);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(snapshot);
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(subscriptions.publications).isZero();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit(snapshot);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(subscriptions.publications).isEqualTo(1);
    }

    private static final class TrackingSubscriptions extends GameSubscriptionRegistry {

        private int publications;

        private TrackingSubscriptions() {
            super(new ObjectMapper());
        }

        @Override
        void publish(GameSnapshot snapshot, String excludedConnectionId) {
            publications++;
        }
    }
}
