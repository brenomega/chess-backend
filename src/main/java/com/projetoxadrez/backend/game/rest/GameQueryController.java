package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyPage;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/games")
public class GameQueryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final GameQueryService service;
    private final GuestSessionService sessionService;

    public GameQueryController(GameQueryService service, GuestSessionService sessionService) {
        this.service = service;
        this.sessionService = sessionService;
    }

    @GetMapping
    public LobbyPage lobby(
            @RequestHeader("X-Session-Token") String recoveryToken,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        sessionService.recover(recoveryToken);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException();
        }
        return service.lobby(PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    @GetMapping("/{gameId}")
    public GameSnapshot game(
            @RequestHeader("X-Session-Token") String recoveryToken,
            @PathVariable UUID gameId) {
        UUID sessionId = sessionService.recover(recoveryToken).sessionId();
        return service.game(gameId, sessionId);
    }

    static final class InvalidPaginationException extends RuntimeException {
    }
}
