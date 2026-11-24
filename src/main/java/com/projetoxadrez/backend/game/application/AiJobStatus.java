package com.projetoxadrez.backend.game.application;

/** Situação de um trabalho de IA (EAP 2.14.2). */
public enum AiJobStatus {
    /** Criado e à espera de um worker. */
    PENDING,
    /** Entregue a um worker, com prazo para devolver o candidato. */
    ASSIGNED,
    /** Candidato aceito e aplicado à partida. */
    COMPLETED,
    /** Descartado: a partida andou sem ele, ou terminou. */
    CANCELLED;

    public boolean isOpen() {
        return this == PENDING || this == ASSIGNED;
    }
}
