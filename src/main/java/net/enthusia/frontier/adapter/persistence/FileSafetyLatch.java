package net.enthusia.frontier.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import net.enthusia.frontier.application.SafetyLatch;

/** File-backed safety latch intentionally independent from the SQLite database it protects. */
public final class FileSafetyLatch implements SafetyLatch {
    private final Path marker;
    private final Consumer<String> errorSink;
    private volatile String inMemoryReason;

    public FileSafetyLatch(Path marker, Consumer<String> errorSink) {
        this.marker = Objects.requireNonNull(marker, "marker");
        this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
        this.inMemoryReason = readExistingReason();
    }

    @Override
    public synchronized void trip(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (inMemoryReason != null) {
            return;
        }
        inMemoryReason = reason;
        try {
            Path parent = marker.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = "tripped-at=" + Instant.now() + System.lineSeparator()
                    + "reason=" + reason + System.lineSeparator();
            Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            errorSink.accept("CRITICAL: could not persist Frontier cleanup safety latch: " + exception);
        }
    }

    @Override
    public boolean isTripped() {
        return inMemoryReason != null;
    }

    @Override
    public String reason() {
        return inMemoryReason == null ? "none" : inMemoryReason;
    }

    private String readExistingReason() {
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            errorSink.accept("Could not read existing Frontier safety latch; treating cleanup as unsafe: " + exception);
            return "safety-latch marker exists but could not be read";
        }
    }
}
