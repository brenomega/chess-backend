package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.GameQueryService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GameQueryExceptionHandler {

    @ExceptionHandler(GameQueryController.InvalidPaginationException.class)
    public ResponseEntity<GameErrorResponse> invalidPagination() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid pagination");
    }

    @ExceptionHandler(GameQueryService.GameNotFoundException.class)
    public ResponseEntity<GameErrorResponse> gameNotFound() {
        return response(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Game not found");
    }

    @ExceptionHandler(GameQueryService.NotAGameParticipantException.class)
    public ResponseEntity<GameErrorResponse> notParticipant() {
        return response(HttpStatus.FORBIDDEN, "NOT_A_PARTICIPANT", "Not a game participant");
    }

    private static ResponseEntity<GameErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new GameErrorResponse(code, message, Map.of()));
    }
}
