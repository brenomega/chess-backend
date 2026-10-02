package com.projetoxadrez.backend.session.rest;

import com.projetoxadrez.backend.session.application.GuestSessionResult;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import com.projetoxadrez.backend.session.application.InvalidSessionRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/sessions")
public class GuestSessionController {

    private final GuestSessionService service;

    public GuestSessionController(GuestSessionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GuestSessionResponse create(@RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) {
            throw new InvalidSessionRequestException();
        }
        return response(service.create());
    }

    @PostMapping("/recover")
    public GuestSessionResponse recover(@RequestBody(required = false) RecoverGuestSessionRequest request) {
        return response(service.recover(request == null ? null : request.recoveryToken()));
    }

    private static GuestSessionResponse response(GuestSessionResult result) {
        return new GuestSessionResponse(result.sessionId(), result.recoveryToken(), result.expiresAt());
    }
}
