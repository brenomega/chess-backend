package com.projetoxadrez.backend.game.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GameQueryBindingExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BindingProbeController())
                .setControllerAdvice(new GameQueryBindingExceptionHandler())
                .build();
    }

    @Test
    void wrapsInvalidIntegerBindingInDocumentedEnvelope() throws Exception {
        mockMvc.perform(get("/binding-probe").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void wrapsInvalidUuidBindingInDocumentedEnvelope() throws Exception {
        mockMvc.perform(get("/binding-probe/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isMap());
    }

    @RestController
    static final class BindingProbeController {

        @GetMapping("/binding-probe")
        void bindPage(@RequestParam int page) {
        }

        @GetMapping("/binding-probe/{gameId}")
        void bindGameId(@PathVariable UUID gameId) {
        }
    }
}
