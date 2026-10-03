package com.projetoxadrez.backend.game.rest;

import java.util.Map;

public record GameErrorResponse(String code, String message, Map<String, Object> details) {
}
