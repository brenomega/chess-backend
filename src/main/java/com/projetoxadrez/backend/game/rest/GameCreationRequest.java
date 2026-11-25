package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameMode;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;

/**
 * Corpo de POST /v1/games.
 *
 * <p>{@code mode} ausente significa humano contra humano, que era o único modo antes da EAP 2.14;
 * clientes antigos continuam funcionando sem mudança. {@code side} escolhe o lado do humano no
 * modo Humano × IA e é ignorado nos outros.
 */
public record GameCreationRequest(
        GameVisibility visibility, TimeControl timeControl, GameMode mode, Side side) {
}
