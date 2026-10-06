package com.projetoxadrez.backend.game.rest;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = GameQueryController.class)
final class GameQueryBindingExceptionHandler {

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class
    })
    ResponseEntity<GameErrorResponse> handleInvalidBinding(Exception exception) {
        return ResponseEntity.badRequest().body(new GameErrorResponse(
                "VALIDATION_ERROR",
                "Invalid request parameters",
                Map.of()));
    }
}
