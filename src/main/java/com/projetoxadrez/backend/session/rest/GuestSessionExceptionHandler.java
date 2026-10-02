package com.projetoxadrez.backend.session.rest;

import com.projetoxadrez.backend.session.application.ExpiredGuestSessionException;
import com.projetoxadrez.backend.session.application.InvalidGuestSessionException;
import com.projetoxadrez.backend.session.application.InvalidSessionRequestException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GuestSessionExceptionHandler {

    @ExceptionHandler({InvalidSessionRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<SessionErrorResponse> invalidRequest() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid session request");
    }

    @ExceptionHandler(InvalidGuestSessionException.class)
    public ResponseEntity<SessionErrorResponse> invalidSession() {
        return response(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Session recovery token is invalid");
    }

    @ExceptionHandler(ExpiredGuestSessionException.class)
    public ResponseEntity<SessionErrorResponse> expiredSession() {
        return response(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session has expired");
    }

    private static ResponseEntity<SessionErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new SessionErrorResponse(code, message, Map.of()));
    }
}
