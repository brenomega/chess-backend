package com.projetoxadrez.backend.game.application;

/**
 * Acerta a fila de trabalhos da IA depois de cada gravação de estado da partida (EAP 2.14.3).
 *
 * <p>É uma porta e não uma classe concreta porque quase toda ação da partida passa por aqui: quem
 * testa uma ação humana não deveria precisar montar a fila da IA junto.
 */
public interface AiTurnScheduler {

    void syncAiTurn(GameSnapshot snapshot);
}
