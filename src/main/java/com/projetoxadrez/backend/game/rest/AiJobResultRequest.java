package com.projetoxadrez.backend.game.rest;

import java.util.UUID;

/** Corpo de POST /v1/ai/jobs/result. */
public record AiJobResultRequest(String workerId, UUID jobId, UUID gameId, Long revision, String uci) {
}
