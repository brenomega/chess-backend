package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record AiJobResultRequest(JsonNode workerId, UUID jobId, UUID gameId, JsonNode revision, String uci) {
}
