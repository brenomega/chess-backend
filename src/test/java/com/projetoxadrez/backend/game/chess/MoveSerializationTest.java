package com.projetoxadrez.backend.game.chess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoveSerializationTest {

    @ParameterizedTest
    @ValueSource(strings = {"e2e4", "e7e8q", "a2b1r", "h7h8b", "b2a1n"})
    void parsesAndSerializesCanonicalUci(String uci) {
        assertThat(Move.fromUci(uci).toUci()).isEqualTo(uci);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "e2", "e2e4qk", "E2E4", "e9e4", "e2e4k", "e2-e4", "e2e2"})
    void rejectsInvalidUci(String uci) {
        assertThatThrownBy(() -> Move.fromUci(uci)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsCurrentGameHistoryAsCanonicalUci() {
        MoveHistory empty = MoveHistory.empty();
        MoveHistory history = empty
                .append(Move.fromUci("e2e4"))
                .append(Move.fromUci("e7e5"))
                .append(Move.fromUci("a7a8q"));

        assertThat(empty.uciMoves()).isEmpty();
        assertThat(history.uciMoves()).containsExactly("e2e4", "e7e5", "a7a8q");
        assertThat(history.lastMoveUci()).contains("a7a8q");
        assertThatThrownBy(() -> history.uciMoves().add("g1f3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidMoveWhenRehydratingHistory() {
        assertThatThrownBy(() -> new MoveHistory(List.of("e2e4", "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
