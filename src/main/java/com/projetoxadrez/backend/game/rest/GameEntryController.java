package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/games")
public class GameEntryController {

    private final GameEntryService service;
    private final GuestSessionService sessionService;

    public GameEntryController(GameEntryService service, GuestSessionService sessionService) {
        this.service = service;
        this.sessionService = sessionService;
    }

    @PostMapping("/{gameId}/join")
    public GameSnapshot join(
            @RequestHeader("X-Session-Token") String recoveryToken,
            @PathVariable UUID gameId,
            @RequestBody(required = false) GameEntryRequest request) {
        UUID sessionId = sessionService.recover(recoveryToken).sessionId();
        return service.join(gameId, sessionId, request == null ? null : request.entryCode());
    }

    @PostMapping("/join")
    public GameSnapshot joinByCode(
            @RequestHeader("X-Session-Token") String recoveryToken,
            @RequestBody(required = false) GameEntryRequest request) {
        UUID sessionId = sessionService.recover(recoveryToken).sessionId();
        return service.joinByCode(sessionId, request == null ? null : request.entryCode());
    }
}
