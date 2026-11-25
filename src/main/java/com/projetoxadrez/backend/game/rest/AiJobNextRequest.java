package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.JsonNode;

public record AiJobNextRequest(JsonNode workerId) {
}
