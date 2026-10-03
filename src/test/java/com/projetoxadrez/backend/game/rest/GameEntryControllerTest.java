package com.projetoxadrez.backend.game.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameEntryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.session.application.GuestSessionProperties;
import com.projetoxadrez.backend.session.application.GuestSessionResult;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import com.projetoxadrez.backend.session.application.InMemoryGuestSessionStore;
import com.projetoxadrez.backend.session.rest.GuestSessionExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GameEntryControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");
    private static final String TOKEN = "Q4qGEqpQ6TBGKs-b0bma-DqXYw-zIr1nR2TQPr3qLxA";

    private InMemoryGameEntryStore store;
    private MockMvc mockMvc;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        store = new InMemoryGameEntryStore();
        GuestSessionService sessionService = new GuestSessionService(
                new InMemoryGuestSessionStore(),
                () -> TOKEN,
                new GuestSessionProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        GuestSessionResult session = sessionService.create();
        sessionId = session.sessionId();
        mockMvc = MockMvcBuilders.standaloneSetup(new GameEntryController(
                        new GameEntryService(store, Clock.fixed(NOW, ZoneOffset.UTC)), sessionService))
                .setControllerAdvice(
                        new GuestSessionExceptionHandler(),
                        new GameQueryExceptionHandler(),
                        new GameQueryBindingExceptionHandler())
                .build();
    }

    @Test
    void joinsAnEligiblePublicGame() throws Exception {
        UUID gameId = UUID.randomUUID();
        store.putSnapshot(gameId, snapshot(gameId, GameVisibility.PUBLIC, null));

        mockMvc.perform(post("/v1/games/{gameId}/join", gameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(gameId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.entryCode").doesNotExist())
                .andExpect(jsonPath("$.players.length()").value(2))
                .andExpect(jsonPath("$.clock.activeSide").value("WHITE"));

        assertThat(store.lastJoin()).isEqualTo(new Join(gameId, sessionId, null, NOW));
    }

    @Test
    void joinsPrivateGameWithMatchingCode() throws Exception {
        UUID gameId = UUID.randomUUID();
        store.putSnapshot(gameId, snapshot(gameId, GameVisibility.PRIVATE, "ABC234"));

        mockMvc.perform(post("/v1/games/{gameId}/join", gameId)
                        .header("X-Session-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryCode\":\"ABC234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.entryCode").value("ABC234"));
    }

    @Test
    void rejectsMissingOrInvalidPrivateCodesWithTheDocumentedErrors() throws Exception {
        UUID missingCodeGameId = UUID.randomUUID();
        UUID invalidCodeGameId = UUID.randomUUID();
        store.putFailure(missingCodeGameId, new GameEntryService.EntryCodeRequiredException());
        store.putFailure(invalidCodeGameId, new GameEntryService.InvalidEntryCodeException());

        mockMvc.perform(post("/v1/games/{gameId}/join", missingCodeGameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTRY_CODE_REQUIRED"))
                .andExpect(jsonPath("$.details").isEmpty());
        mockMvc.perform(post("/v1/games/{gameId}/join", invalidCodeGameId)
                        .header("X-Session-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryCode\":\"XYZ789\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_ENTRY_CODE"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void rejectsGamesThatAreNotJoinableWithTheDocumentedError() throws Exception {
        UUID gameId = UUID.randomUUID();
        store.putFailure(gameId, new GameEntryService.GameNotJoinableException());

        mockMvc.perform(post("/v1/games/{gameId}/join", gameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_NOT_JOINABLE"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private static GameSnapshot snapshot(UUID gameId, GameVisibility visibility, String entryCode) {
        return new GameSnapshot(
                gameId,
                GameStatus.ACTIVE,
                visibility,
                entryCode,
                1,
                List.of(
                        new GameSnapshot.Player(Side.WHITE, "HUMAN"),
                        new GameSnapshot.Player(Side.BLACK, "HUMAN")),
                new GameSnapshot.Position(
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                        Side.WHITE,
                        null),
                new GameSnapshot.Clock(600_000, 600_000, Side.WHITE),
                null,
                null);
    }

    private static final class InMemoryGameEntryStore implements GameEntryStore {

        private final Map<UUID, GameSnapshot> snapshots = new java.util.HashMap<>();
        private final Map<UUID, RuntimeException> failures = new java.util.HashMap<>();
        private Join lastJoin;

        void putSnapshot(UUID gameId, GameSnapshot snapshot) {
            snapshots.put(gameId, snapshot);
        }

        void putFailure(UUID gameId, RuntimeException exception) {
            failures.put(gameId, exception);
        }

        Join lastJoin() {
            return lastJoin;
        }

        @Override
        public GameSnapshot join(UUID gameId, UUID sessionId, String entryCode, Instant now) {
            lastJoin = new Join(gameId, sessionId, entryCode, now);
            RuntimeException exception = failures.get(gameId);
            if (exception != null) {
                throw exception;
            }
            return snapshots.get(gameId);
        }
    }

    private record Join(UUID gameId, UUID sessionId, String entryCode, Instant now) {
    }
}
