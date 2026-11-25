package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mantém a fila de trabalhos da IA alinhada com o estado da partida (EAP 2.14.3).
 *
 * <p>A regra é só uma: existe trabalho aberto se, e somente se, a partida está ativa e a vez é de
 * um participante controlado por IA — na revisão atual. Qualquer outra coisa é trabalho obsoleto,
 * que é cancelado em vez de ser entregue.
 */
@Service
public class AiJobTurnScheduler implements AiTurnScheduler {

    private final AiJobStore store;
    private final Clock clock;
    private final long timeBudgetMs;

    public AiJobTurnScheduler(
            AiJobStore store, Clock clock, @Value("${chess.ai.time-budget-ms:1000}") long timeBudgetMs) {
        this.store = store;
        this.clock = clock;
        this.timeBudgetMs = timeBudgetMs;
    }

    @Override
    public void syncAiTurn(GameSnapshot snapshot) {
        Instant now = clock.instant();
        Optional<Side> aiSide = aiSideToMove(snapshot);
        if (aiSide.isEmpty()) {
            store.cancelOpenJobs(snapshot.gameId(), now);
            return;
        }

        Optional<AiJobState> open = store.findOpenJob(snapshot.gameId());
        if (open.isPresent() && open.get().revision() == snapshot.revision()) {
            return;
        }
        // Trabalho de uma revisão que já passou: a partida andou sem ele (uma oferta de empate,
        // por exemplo). Entregá-lo só produziria STALE_REVISION na devolução.
        open.ifPresent(job -> store.cancel(job.jobId(), now));
        store.enqueue(
                snapshot.gameId(),
                snapshot.revision(),
                snapshot.position().fen(),
                aiSide.get(),
                timeBudgetMs,
                now);
    }

    private static Optional<Side> aiSideToMove(GameSnapshot snapshot) {
        if (snapshot.status() != GameStatus.ACTIVE) {
            return Optional.empty();
        }
        Side activeSide = snapshot.clock().activeSide();
        if (activeSide == null) {
            return Optional.empty();
        }
        return snapshot.players().stream()
                .filter(player -> player.side() == activeSide && "AI".equals(player.kind()))
                .map(GameSnapshot.Player::side)
                .findFirst();
    }
}
