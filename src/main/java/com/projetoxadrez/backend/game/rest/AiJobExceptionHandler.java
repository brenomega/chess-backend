package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.AiJobService;
import com.projetoxadrez.backend.game.application.GameActionService;
import com.projetoxadrez.backend.game.domain.Match;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(assignableTypes = AiJobController.class)
public class AiJobExceptionHandler {

    @ExceptionHandler({AiJobService.InvalidResultException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<GameErrorResponse> invalidRequest() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid AI job request");
    }

    @ExceptionHandler(GameActionService.InvalidMoveFormatException.class)
    public ResponseEntity<GameErrorResponse> invalidCandidate() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_CANDIDATE", "Candidate is not in UCI notation");
    }

    @ExceptionHandler({AiJobService.JobNotFoundException.class, GameActionService.GameNotFoundException.class})
    public ResponseEntity<GameErrorResponse> jobNotFound() {
        return response(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found");
    }

    @ExceptionHandler(AiJobService.JobExpiredException.class)
    public ResponseEntity<GameErrorResponse> jobExpired() {
        return response(HttpStatus.CONFLICT, "JOB_EXPIRED", "Job is no longer valid");
    }

    @ExceptionHandler({AiJobService.StaleJobRevisionException.class, Match.StaleRevisionException.class})
    public ResponseEntity<GameErrorResponse> staleRevision() {
        return response(HttpStatus.CONFLICT, "STALE_REVISION", "Job no longer matches the game revision");
    }

    @ExceptionHandler(AiJobService.JobAlreadyCompletedException.class)
    public ResponseEntity<GameErrorResponse> alreadyCompleted() {
        return response(HttpStatus.CONFLICT, "JOB_ALREADY_COMPLETED", "Job already produced a move");
    }

    @ExceptionHandler(AiJobService.WorkerOwnershipConflictException.class)
    public ResponseEntity<GameErrorResponse> ownershipConflict() {
        return response(HttpStatus.CONFLICT, "WORKER_OWNERSHIP_CONFLICT", "Job belongs to another worker");
    }

    @ExceptionHandler(Match.IllegalMoveException.class)
    public ResponseEntity<GameErrorResponse> illegalMove() {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_MOVE", "Candidate is not legal in this position");
    }

    @ExceptionHandler({
        Match.InvalidGameStateException.class,
        Match.GameTimedOutException.class,
        Match.NotYourTurnException.class,
        Match.NotAParticipantException.class
    })
    public ResponseEntity<GameErrorResponse> invalidGameState() {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GAME_STATE", "Game no longer accepts this move");
    }

    private static ResponseEntity<GameErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new GameErrorResponse(code, message, Map.of()));
    }
}
