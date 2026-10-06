package com.projetoxadrez.backend.session.rest;

import java.util.Map;

public record SessionErrorResponse(String code, String message, Map<String, Object> details) {
}
