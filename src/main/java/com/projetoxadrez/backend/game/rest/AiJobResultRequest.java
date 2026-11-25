package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.JsonNode;

public record AiJobResultRequest(JsonNode workerId, JsonNode jobId, JsonNode gameId, JsonNode revision, JsonNode uci) {
}
