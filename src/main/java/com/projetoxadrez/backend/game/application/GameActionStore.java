package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Match;
import java.util.Optional;
import java.util.UUID;

/** Acesso à partida em andamento, para as ações executadas durante o jogo (EAP 2.9). */
public interface GameActionStore {

    /**
     * Carrega a partida para alteração. A implementação toma trava pessimista na linha, de modo
     * que duas ações concorrentes na mesma partida sejam serializadas (EAP 2.11).
     */
    Optional<Match> findForUpdate(UUID gameId);

    GameSnapshot save(Match match);
}
