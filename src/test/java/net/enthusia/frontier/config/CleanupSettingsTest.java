package net.enthusia.frontier.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CleanupSettingsTest {
    @Test
    void acceptsSafeDryRunAndActiveConfigurations() {
        assertDoesNotThrow(() -> new CleanupSettings(false, true, 30, 1200, 128, 1, 8, 35.0, false, true, true));
        assertDoesNotThrow(() -> new CleanupSettings(true, false, 0, 20, 1, 64, 0, 50.0, true, true, true));
    }

    @Test
    void rejectsUnsafeBoundsAndImpossibleDisabledDestructiveMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(false, false, 30, 1200, 128, 1, 8, 35.0, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, -1, 1200, 128, 1, 8, 35.0, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, 30, 19, 128, 1, 8, 35.0, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, 30, 1200, 0, 1, 8, 35.0, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, 30, 1200, 128, 0, 8, 35.0, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, 30, 1200, 128, 1, 129, 35.0, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, 30, 1200, 128, 1, 8, Double.NaN, false, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CleanupSettings(true, false, 30, 1200, 128, 1, 8, 50.1, false, true, true));
    }
}
