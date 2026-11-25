package com.projetoxadrez.backend.game.rest;

import java.util.UUID;

/** Confirmação de candidato aplicado. */
public record AiJobResultResponse(String status, UUID gameId, long revision) {
}
