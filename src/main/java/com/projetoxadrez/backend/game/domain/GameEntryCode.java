package com.projetoxadrez.backend.game.domain;

import java.security.SecureRandom;
import java.util.Objects;

public record GameEntryCode(String value) {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public GameEntryCode {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.matches("[A-HJ-NP-Z2-9]{6}")) {
            throw new IllegalArgumentException("Entry code must contain six unambiguous uppercase characters");
        }
    }

    public static GameEntryCode newCode() {
        char[] characters = new char[LENGTH];
        for (int index = 0; index < characters.length; index++) {
            characters[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new GameEntryCode(new String(characters));
    }
}
