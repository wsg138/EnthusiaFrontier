package net.enthusia.frontier.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSafetyLatchTest {
    @TempDir
    Path tempDirectory;

    @Test
    void tripPersistsAndExistingMarkerFailsClosedAcrossRestart() throws Exception {
        Path marker = tempDirectory.resolve("state/CLEANUP_UNSAFE.latch");
        List<String> errors = new ArrayList<>();
        FileSafetyLatch latch = new FileSafetyLatch(marker, errors::add);

        assertFalse(latch.isTripped());
        assertEquals("none", latch.reason());

        latch.trip("ledger write uncertain");
        assertTrue(latch.isTripped());
        assertEquals("ledger write uncertain", latch.reason());
        assertTrue(Files.readString(marker).contains("reason=ledger write uncertain"));
        String firstContents = Files.readString(marker);

        latch.trip("second reason must not replace first");
        assertEquals(firstContents, Files.readString(marker));
        assertTrue(errors.isEmpty());

        FileSafetyLatch reloaded = new FileSafetyLatch(marker, errors::add);
        assertTrue(reloaded.isTripped());
        assertTrue(reloaded.reason().contains("reason=ledger write uncertain"));
    }

    @Test
    void persistenceFailureStillTripsInMemoryAndReportsCriticalError() throws Exception {
        Path parentFile = tempDirectory.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied");
        List<String> errors = new ArrayList<>();
        FileSafetyLatch latch = new FileSafetyLatch(parentFile.resolve("latch"), errors::add);

        latch.trip("unsafe even when marker write fails");

        assertTrue(latch.isTripped());
        assertEquals("unsafe even when marker write fails", latch.reason());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("CRITICAL: could not persist Frontier cleanup safety latch:"));
    }
}
