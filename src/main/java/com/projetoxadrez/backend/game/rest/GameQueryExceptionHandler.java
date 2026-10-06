package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameCreationService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GameQueryExceptionHandler {

    @ExceptionHandler(GameCreationService.InvalidGameCreationException.class)
    public ResponseEntity<GameErrorResponse> invalidGameCreation() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid game creation request");
    }

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

    @ExceptionHandler(GameEntryService.GameNotFoundException.class)
    public ResponseEntity<GameErrorResponse> entryGameNotFound() {
        return response(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", "Game not found");
    }

    @ExceptionHandler(GameEntryService.EntryCodeRequiredException.class)
    public ResponseEntity<GameErrorResponse> entryCodeRequired() {
        return response(HttpStatus.BAD_REQUEST, "ENTRY_CODE_REQUIRED", "Entry code is required");
    }

    @ExceptionHandler(GameEntryService.UnexpectedEntryCodeException.class)
    public ResponseEntity<GameErrorResponse> unexpectedEntryCode() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Entry code is not allowed for public games");
    }

    @ExceptionHandler(GameEntryService.InvalidEntryCodeException.class)
    public ResponseEntity<GameErrorResponse> invalidEntryCode() {
        return response(HttpStatus.FORBIDDEN, "INVALID_ENTRY_CODE", "Entry code is invalid");
    }

    @ExceptionHandler(GameEntryService.GameNotJoinableException.class)
    public ResponseEntity<GameErrorResponse> gameNotJoinable() {
        return response(HttpStatus.CONFLICT, "GAME_NOT_JOINABLE", "Game is not joinable");
    }

    private static ResponseEntity<GameErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new GameErrorResponse(code, message, Map.of()));
    }
}
