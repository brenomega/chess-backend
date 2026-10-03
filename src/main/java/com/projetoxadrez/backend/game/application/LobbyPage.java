package com.projetoxadrez.backend.game.application;

import java.util.List;

public record LobbyPage(List<LobbyGame> games, int page, int size, long totalElements, int totalPages) {

    public LobbyPage {
        games = List.copyOf(games);
    }
}
