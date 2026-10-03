package com.projetoxadrez.backend.game.domain;

import java.util.Set;

public record TimeControl(long initialTimeMs, long incrementMs) {

    public static final long INFINITE_INITIAL_TIME_MS = 0;
    public static final long THREE_MINUTES_INITIAL_TIME_MS = 180_000;
    public static final long TEN_MINUTES_INITIAL_TIME_MS = 600_000;
    public static final long SIXTY_MINUTES_INITIAL_TIME_MS = 3_600_000;

    private static final Set<Long> SUPPORTED_INITIAL_TIMES = Set.of(
            INFINITE_INITIAL_TIME_MS,
            THREE_MINUTES_INITIAL_TIME_MS,
            TEN_MINUTES_INITIAL_TIME_MS,
            SIXTY_MINUTES_INITIAL_TIME_MS);

    public TimeControl {
        if (!SUPPORTED_INITIAL_TIMES.contains(initialTimeMs)) {
            throw new IllegalArgumentException("Time control must be 3, 10, 60 minutes or infinite");
        }
        if (incrementMs != 0) {
            throw new IllegalArgumentException("Time control increment must be zero");
        }
    }

    public boolean isInfinite() {
        return initialTimeMs == INFINITE_INITIAL_TIME_MS;
    }
}
