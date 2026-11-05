package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class RestartControllerTest {
    private static final LocalTime MIDNIGHT = LocalTime.of(0, 0);

    @Test
    void nextMidnightIsTomorrow() {
        assertEquals(Instant.parse("2026-11-06T00:00:00Z"),
                RestartController.nextDaily(Instant.parse("2026-11-05T18:30:00Z"), MIDNIGHT));
    }

    @Test
    void exactlyAtTimeMeansNextDay() {
        assertEquals(Instant.parse("2026-11-06T00:00:00Z"),
                RestartController.nextDaily(Instant.parse("2026-11-05T00:00:00Z"), MIDNIGHT));
    }

    @Test
    void laterTimeSameDay() {
        assertEquals(Instant.parse("2026-11-05T21:00:00Z"),
                RestartController.nextDaily(Instant.parse("2026-11-05T18:30:00Z"), LocalTime.of(21, 0)));
    }
}
