package com.projetoxadrez.backend.game.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projetoxadrez.backend.game.application.GameCreationService;
import com.projetoxadrez.backend.game.application.GameCreationStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameEntryCode;
import com.projetoxadrez.backend.session.application.GuestSessionProperties;
import com.projetoxadrez.backend.session.application.GuestSessionResult;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import com.projetoxadrez.backend.session.application.InMemoryGuestSessionStore;
import com.projetoxadrez.backend.session.rest.GuestSessionExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GameCreationControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");
    private static final String TOKEN = "Q4qGEqpQ6TBGKs-b0bma-DqXYw-zIr1nR2TQPr3qLxA";

    private InMemoryGameCreationStore store;
    private MockMvc mockMvc;
    private GuestSessionResult session;

    @BeforeEach
    void setUp() {
        store = new InMemoryGameCreationStore();
        GuestSessionService sessionService = new GuestSessionService(
                new InMemoryGuestSessionStore(),
                () -> TOKEN,
                new GuestSessionProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        session = sessionService.create();
        mockMvc = MockMvcBuilders.standaloneSetup(new GameCreationController(
                        new GameCreationService(store, Clock.fixed(NOW, ZoneOffset.UTC)), sessionService))
                .setControllerAdvice(
                        new GuestSessionExceptionHandler(),
                        new GameQueryExceptionHandler(),
                        new GameQueryBindingExceptionHandler())
                .build();
    }

    @Test
    void createsPublicWaitingGameForTheGuestSession() throws Exception {
        mockMvc.perform(post("/v1/games")
                        .header("X-Session-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visibility":"PUBLIC",
                                  "timeControl":{"initialTimeMs":600000,"incrementMs":0}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.entryCode").doesNotExist())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].side").value("WHITE"))
                .andExpect(jsonPath("$.players[0].kind").value("HUMAN"))
                .andExpect(jsonPath("$.clock.activeSide").doesNotExist());

        assertThat(store.createdGame().participants()).containsExactly(
                new Game.Participant(Side.WHITE, session.sessionId(), "HUMAN"));
        assertThat(store.createdAt()).isEqualTo(NOW);
    }

    @Test
    void createsPrivateWaitingGameWithOpaqueSixCharacterCode() throws Exception {
        mockMvc.perform(post("/v1/games")
                        .header("X-Session-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visibility":"PRIVATE",
                                  "timeControl":{"initialTimeMs":180000,"incrementMs":0}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.entryCode").value(org.hamcrest.Matchers.matchesPattern(
                        "[A-HJ-NP-Z2-9]{6}")))
                .andExpect(jsonPath("$.players.length()").value(1));
    }

    @Test
    void requiresAValidGuestSession() throws Exception {
        mockMvc.perform(post("/v1/games")
                        .header("X-Session-Token", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visibility":"PUBLIC",
                                  "timeControl":{"initialTimeMs":600000,"incrementMs":0}
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));

        assertThat(store.createdGame()).isNull();
    }

    @Test
    void rejectsMissingCreationConfiguration() throws Exception {
        mockMvc.perform(post("/v1/games")
                        .header("X-Session-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private static GameSnapshot snapshot(Game game) {
        long initialTimeMs = game.timeControl().initialTimeMs();
        return new GameSnapshot(
                game.id().value(),
                game.status(),
                game.visibility(),
                game.entryCode().map(GameEntryCode::value).orElse(null),
                game.revision(),
                game.participants().stream()
                        .map(participant -> new GameSnapshot.Player(participant.side(), participant.kind()))
                        .toList(),
                new GameSnapshot.Position(ChessPosition.initial().toFen(), Side.WHITE, null),
                new GameSnapshot.Clock(initialTimeMs, initialTimeMs, null),
                null,
                null);
    }

    private static final class InMemoryGameCreationStore implements GameCreationStore {

        private Game createdGame;
        private Instant createdAt;

        @Override
        public GameSnapshot create(Game game, Instant instant) {
            createdGame = game;
            createdAt = instant;
            return snapshot(game);
        }

        Game createdGame() {
            return createdGame;
        }

        Instant createdAt() {
            return createdAt;
        }
    }
}
