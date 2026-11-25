package com.projetoxadrez.backend.game.domain;

/**
 * Como a partida é composta (EAP 2.14.1).
 *
 * <p>Só o modo humano contra humano espera um segundo jogador: nos outros dois os participantes
 * já existem na criação, então a partida nasce ativa.
 */
public enum GameMode {
    HUMAN_VS_HUMAN,
    HUMAN_VS_AI,
    AI_VS_AI;

    public boolean hasAi() {
        return this != HUMAN_VS_HUMAN;
    }
}
