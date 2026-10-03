package com.projetoxadrez.backend.game.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projetoxadrez.backend.game.application.GameQueryStore;
import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.chess.Side;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GameQueryControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-02T15:00:00Z");
    private static final String TOKEN = "Q4qGEqpQ6TBGKs-b0bma-DqXYw-zIr1nR2TQPr3qLxA";

    private InMemoryGameQueryStore gameStore;
    private MockMvc mockMvc;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        gameStore = new InMemoryGameQueryStore();
        GuestSessionService sessionService = new GuestSessionService(
                new InMemoryGuestSessionStore(),
                () -> TOKEN,
                new GuestSessionProperties(),
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));
        GuestSessionResult session = sessionService.create();
        sessionId = session.sessionId();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GameQueryController(new GameQueryService(gameStore), sessionService))
                .setControllerAdvice(new GuestSessionExceptionHandler(), new GameQueryExceptionHandler())
                .build();
    }

    @Test
    void listsOnlyPublicWaitingGamesInPages() throws Exception {
        UUID firstGameId = UUID.randomUUID();
        UUID secondGameId = UUID.randomUUID();
        gameStore.setLobbyGames(List.of(
                lobbyGame(firstGameId, GameStatus.WAITING, GameVisibility.PUBLIC, CREATED_AT),
                lobbyGame(UUID.randomUUID(), GameStatus.ACTIVE, GameVisibility.PUBLIC, CREATED_AT.minusSeconds(1)),
                lobbyGame(UUID.randomUUID(), GameStatus.WAITING, GameVisibility.PRIVATE, CREATED_AT.minusSeconds(2)),
                lobbyGame(secondGameId, GameStatus.WAITING, GameVisibility.PUBLIC, CREATED_AT.minusSeconds(3))));

        mockMvc.perform(get("/v1/games?page=1&size=1").header("X-Session-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games.length()").value(1))
                .andExpect(jsonPath("$.games[0].gameId").value(secondGameId.toString()))
                .andExpect(jsonPath("$.games[0].status").value("WAITING"))
                .andExpect(jsonPath("$.games[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.games[0].timeControl.initialTimeMs").value(600000))
                .andExpect(jsonPath("$.games[0].createdAt").value("2026-10-02T14:59:57Z"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void returnsAConsistentSnapshotOnlyToAParticipant() throws Exception {
        UUID gameId = UUID.randomUUID();
        gameStore.putSnapshot(gameId, sessionId, snapshot(gameId));

        mockMvc.perform(get("/v1/games/{gameId}", gameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(gameId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.entryCode").value("G7K2XP"))
                .andExpect(jsonPath("$.revision").value(12))
                .andExpect(jsonPath("$.players[0].side").value("WHITE"))
                .andExpect(jsonPath("$.players[0].kind").value("HUMAN"))
                .andExpect(jsonPath("$.players[1].side").value("BLACK"))
                .andExpect(jsonPath("$.players[1].kind").value("AI"))
                .andExpect(jsonPath("$.position.fen").value("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"))
                .andExpect(jsonPath("$.position.sideToMove").value("WHITE"))
                .andExpect(jsonPath("$.position.lastMoveUci").value("e7e5"))
                .andExpect(jsonPath("$.clock.whiteRemainingMs").value(598321))
                .andExpect(jsonPath("$.clock.blackRemainingMs").value(599004))
                .andExpect(jsonPath("$.clock.activeSide").value("WHITE"))
                .andExpect(jsonPath("$.drawOffer.offeredBy").value("BLACK"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void doesNotExposeSnapshotsToNonParticipantsAndDistinguishesUnknownGames() throws Exception {
        UUID gameId = UUID.randomUUID();
        gameStore.putSnapshot(gameId, UUID.randomUUID(), snapshot(gameId));

        mockMvc.perform(get("/v1/games/{gameId}", gameId).header("X-Session-Token", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_A_PARTICIPANT"))
                .andExpect(jsonPath("$.message").value("Not a game participant"))
                .andExpect(jsonPath("$.details").isEmpty());
        mockMvc.perform(get("/v1/games/{gameId}", UUID.randomUUID()).header("X-Session-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Game not found"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void rejectsInvalidPaginationWithTheDocumentedErrorContract() throws Exception {
        mockMvc.perform(get("/v1/games?page=-1&size=101").header("X-Session-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid pagination"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private static LobbyGame lobbyGame(UUID id, GameStatus status, GameVisibility visibility, Instant createdAt) {
        return new LobbyGame(id, status, visibility, new TimeControl(600_000, 0), createdAt);
    }

    private static GameSnapshot snapshot(UUID gameId) {
        return new GameSnapshot(
                gameId,
                GameStatus.ACTIVE,
                GameVisibility.PRIVATE,
                "G7K2XP",
                12,
                List.of(
                        new GameSnapshot.Player(Side.WHITE, "HUMAN"),
                        new GameSnapshot.Player(Side.BLACK, "AI")),
                new GameSnapshot.Position(
                        "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                        Side.WHITE,
                        "e7e5"),
                new GameSnapshot.Clock(598_321, 599_004, Side.WHITE),
                new GameSnapshot.DrawOffer(Side.BLACK),
                null);
    }

    private static final class InMemoryGameQueryStore implements GameQueryStore {

        private List<LobbyGame> lobbyGames = List.of();
        private final Map<UUID, SnapshotAccess> snapshots = new java.util.HashMap<>();

        void setLobbyGames(List<LobbyGame> games) {
            lobbyGames = List.copyOf(games);
        }

        void putSnapshot(UUID gameId, UUID participantSessionId, GameSnapshot snapshot) {
            snapshots.put(gameId, new SnapshotAccess(participantSessionId, snapshot));
        }

        @Override
        public Page<LobbyGame> findPublicWaitingGames(Pageable pageable) {
            List<LobbyGame> eligible = lobbyGames.stream()
                    .filter(game -> game.status() == GameStatus.WAITING)
                    .filter(game -> game.visibility() == GameVisibility.PUBLIC)
                    .toList();
            int start = Math.min((int) pageable.getOffset(), eligible.size());
            int end = Math.min(start + pageable.getPageSize(), eligible.size());
            return new PageImpl<>(eligible.subList(start, end), pageable, eligible.size());
        }

        @Override
        public Optional<GameSnapshot> findSnapshot(UUID gameId, UUID requestedSessionId) {
            return Optional.ofNullable(snapshots.get(gameId))
                    .filter(snapshot -> snapshot.sessionId().equals(requestedSessionId))
                    .map(SnapshotAccess::snapshot);
        }

        @Override
        public boolean existsById(UUID gameId) {
            return snapshots.containsKey(gameId);
        }

        private record SnapshotAccess(UUID sessionId, GameSnapshot snapshot) {
        }
    }
}
