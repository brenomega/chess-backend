package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;

public record GameCreationRequest(GameVisibility visibility, TimeControl timeControl) {
}
