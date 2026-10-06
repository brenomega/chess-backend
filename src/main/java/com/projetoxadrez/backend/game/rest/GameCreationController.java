package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.GameCreationService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/games")
public class GameCreationController {

    private final GameCreationService service;
    private final GuestSessionService sessionService;

    public GameCreationController(GameCreationService service, GuestSessionService sessionService) {
        this.service = service;
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GameSnapshot create(
            @RequestHeader("X-Session-Token") String recoveryToken,
            @RequestBody(required = false) GameCreationRequest request) {
        UUID sessionId = sessionService.recover(recoveryToken).sessionId();
        return service.create(
                sessionId,
                request == null ? null : request.visibility(),
                request == null ? null : request.timeControl());
    }
}
