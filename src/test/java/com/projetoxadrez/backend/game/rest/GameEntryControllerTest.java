package com.projetoxadrez.backend.game.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameEntryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameEntryCode;
import com.projetoxadrez.backend.game.domain.GameId;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
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
import java.util.Optional;
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
    private List<GameSnapshot> publishedSnapshots;

    @BeforeEach
    void setUp() {
        store = new InMemoryGameEntryStore();
        publishedSnapshots = new java.util.ArrayList<>();
        GuestSessionService sessionService = new GuestSessionService(
                new InMemoryGuestSessionStore(),
                () -> TOKEN,
                new GuestSessionProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        GuestSessionResult session = sessionService.create();
        sessionId = session.sessionId();
        mockMvc = MockMvcBuilders.standaloneSetup(new GameEntryController(
                        new GameEntryService(
                                store,
                                publishedSnapshots::add,
                                Clock.fixed(NOW, ZoneOffset.UTC)),
                        sessionService))
                .setControllerAdvice(
                        new GuestSessionExceptionHandler(),
                        new GameQueryExceptionHandler(),
                        new GameQueryBindingExceptionHandler())
                .build();
    }

    @Test
    void joinsAnEligiblePublicGame() throws Exception {
        UUID gameId = UUID.randomUUID();
        store.putGame(waitingGame(gameId, GameVisibility.PUBLIC, null));

        mockMvc.perform(post("/v1/games/{gameId}/join", gameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(gameId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.entryCode").doesNotExist())
                .andExpect(jsonPath("$.players.length()").value(2))
                .andExpect(jsonPath("$.clock.activeSide").value("WHITE"));

        assertThat(store.lastSaved().participants().get(1).sessionId()).isEqualTo(sessionId);
        assertThat(store.lastSaved().clockUpdatedAt()).isEqualTo(NOW);
        assertThat(publishedSnapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.gameId()).isEqualTo(gameId);
            assertThat(snapshot.status()).isEqualTo(GameStatus.ACTIVE);
            assertThat(snapshot.revision()).isEqualTo(1);
        });
    }

    @Test
    void joinsPrivateGameWithMatchingCode() throws Exception {
        UUID gameId = UUID.randomUUID();
        store.putGame(waitingGame(gameId, GameVisibility.PRIVATE, "ABC234"));

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
        store.putGame(waitingGame(missingCodeGameId, GameVisibility.PRIVATE, "ABC234"));
        store.putGame(waitingGame(invalidCodeGameId, GameVisibility.PRIVATE, "ABC234"));

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
        store.putGame(game(
                gameId,
                GameStatus.ACTIVE,
                GameVisibility.PUBLIC,
                null,
                List.of(new Game.Participant(Side.WHITE, UUID.randomUUID(), "HUMAN"))));

        mockMvc.perform(post("/v1/games/{gameId}/join", gameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_NOT_JOINABLE"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private static GameSnapshot snapshot(Game game) {
        return new GameSnapshot(
                game.id().value(),
                game.status(),
                game.visibility(),
                game.entryCode().map(GameEntryCode::value).orElse(null),
                game.revision(),
                game.participants().stream()
                        .map(participant -> new GameSnapshot.Player(participant.side(), participant.kind()))
                        .toList(),
                new GameSnapshot.Position(
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                        Side.WHITE,
                        null),
                new GameSnapshot.Clock(600_000, 600_000, game.activeSide()),
                null,
                null);
    }

    private static Game waitingGame(UUID gameId, GameVisibility visibility, String entryCode) {
        return game(
                gameId,
                GameStatus.WAITING,
                visibility,
                entryCode,
                List.of(new Game.Participant(Side.WHITE, UUID.randomUUID(), "HUMAN")));
    }

    private static Game game(
            UUID gameId,
            GameStatus status,
            GameVisibility visibility,
            String entryCode,
            List<Game.Participant> participants) {
        return Game.rehydrate(
                new GameId(gameId),
                status,
                visibility,
                entryCode == null ? null : new GameEntryCode(entryCode),
                new TimeControl(600_000, 0),
                0,
                participants,
                status == GameStatus.ACTIVE ? Side.WHITE : null,
                status == GameStatus.ACTIVE ? NOW : null);
    }

    private static final class InMemoryGameEntryStore implements GameEntryStore {

        private final Map<UUID, Game> games = new java.util.HashMap<>();
        private Game lastSaved;

        void putGame(Game game) {
            games.put(game.id().value(), game);
        }

        Game lastSaved() {
            return lastSaved;
        }

        @Override
        public Optional<Game> findById(UUID gameId) {
            return Optional.ofNullable(games.get(gameId));
        }

        @Override
        public GameSnapshot save(Game game) {
            lastSaved = game;
            return snapshot(game);
        }
    }
}
