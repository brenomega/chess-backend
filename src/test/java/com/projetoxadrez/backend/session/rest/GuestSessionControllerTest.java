package com.projetoxadrez.backend.session.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projetoxadrez.backend.session.application.GuestSessionProperties;
import com.projetoxadrez.backend.session.application.GuestSessionResult;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import com.projetoxadrez.backend.session.application.InMemoryGuestSessionStore;
import com.projetoxadrez.backend.session.persistence.GuestSessionEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GuestSessionControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");
    private static final String TOKEN = "Q4qGEqpQ6TBGKs-b0bma-DqXYw-zIr1nR2TQPr3qLxA";

    private InMemoryGuestSessionStore store;
    private GuestSessionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryGuestSessionStore();
        service = new GuestSessionService(
                store,
                () -> TOKEN,
                new GuestSessionProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(new GuestSessionController(service))
                .setControllerAdvice(new GuestSessionExceptionHandler())
                .build();
    }

    @Test
    void createsGuestSessionAccordingToRestContract() throws Exception {
        mockMvc.perform(post("/v1/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.recoveryToken").value(TOKEN))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-01T15:00:00Z"));
    }

    @Test
    void recoversGuestSessionAccordingToRestContract() throws Exception {
        GuestSessionResult created = service.create();

        mockMvc.perform(post("/v1/sessions/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recoveryToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(created.sessionId().toString()))
                .andExpect(jsonPath("$.recoveryToken").value(TOKEN))
                .andExpect(jsonPath("$.expiresAt").value(created.expiresAt().toString()));
    }

    @Test
    void rejectsInvalidRequestAndInvalidOrExpiredSessions() throws Exception {
        store.save(new GuestSessionEntity(
                UUID.randomUUID(), sha256("expired"), NOW, NOW.minusSeconds(60), NOW.minusSeconds(30)));

        expectError("", 400, "VALIDATION_ERROR");
        expectError("{\"recoveryToken\":\" \"}", 400, "VALIDATION_ERROR");
        expectError("{\"recoveryToken\":\"invalid\"}", 401, "SESSION_INVALID");
        expectError("{\"recoveryToken\":\"expired\"}", 401, "SESSION_EXPIRED");
    }

    @Test
    void rejectsBodyWhenCreatingGuestSession() throws Exception {
        mockMvc.perform(post("/v1/sessions")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("unexpected"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private void expectError(String content, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(post("/v1/sessions/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
