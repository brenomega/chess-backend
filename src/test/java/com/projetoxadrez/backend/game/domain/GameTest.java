package com.projetoxadrez.backend.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GameTest {

    @Test
    void createsPublicGameWithIdentifierAndWaitingState() {
        Game game = Game.create(GameVisibility.PUBLIC);

        assertThat(game.id().value()).isNotNull();
        assertThat(game.status()).isEqualTo(GameStatus.WAITING);
        assertThat(game.visibility()).isEqualTo(GameVisibility.PUBLIC);
        assertThat(game.entryCode()).isEmpty();
    }

    @Test
    void createsPrivateGameWithEntryCode() {
        Game game = Game.create(GameVisibility.PRIVATE);

        assertThat(game.status()).isEqualTo(GameStatus.WAITING);
        assertThat(game.visibility()).isEqualTo(GameVisibility.PRIVATE);
        assertThat(game.entryCode()).hasValueSatisfying(code ->
                assertThat(code.value()).matches("[A-HJ-NP-Z2-9]{6}"));
    }

    @Test
    void rejectsMissingVisibilityAndInvalidEntryCodes() {
        assertThatThrownBy(() -> Game.create(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GameEntryCode("ABC123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameEntryCode("abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameEntryCode(null))
                .isInstanceOf(NullPointerException.class);
    }
}
