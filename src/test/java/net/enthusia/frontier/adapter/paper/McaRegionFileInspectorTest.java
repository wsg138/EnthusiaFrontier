package net.enthusia.frontier.adapter.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McaRegionFileInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsLocationTableAndPreservesNegativeCoordinateSemantics() throws Exception {
        Path region = temporaryDirectory.resolve("r.-1.-2.mca");
        byte[] bytes = new byte[McaRegionFileInspector.HEADER_BYTES];
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        header.putInt(1023 * Integer.BYTES, 0x00000201);
        Files.write(region, bytes);

        assertTrue(McaRegionFileInspector.containsChunk(region, -1, -33));
        assertFalse(McaRegionFileInspector.containsChunk(region, -32, -64));
        assertEquals(
                List.of(new McaRegionFileInspector.Coordinate(-1, -33)),
                McaRegionFileInspector.occupiedChunks(temporaryDirectory, region, -1, -2));
        assertFalse(McaRegionFileInspector.isCompletelyEmpty(temporaryDirectory, region, -1, -2));
    }

    @Test
    void emptyRegionIsReclaimableOnlyWithoutExternalSidecarsOrAlternateContainers() throws Exception {
        Path region = temporaryDirectory.resolve("r.2.3.mca");
        Files.write(region, new byte[McaRegionFileInspector.HEADER_BYTES]);
        assertTrue(McaRegionFileInspector.isCompletelyEmpty(temporaryDirectory, region, 2, 3));

        Path sidecar = temporaryDirectory.resolve("c.64.96.mcc");
        Files.write(sidecar, new byte[]{1});
        assertFalse(McaRegionFileInspector.isCompletelyEmpty(temporaryDirectory, region, 2, 3));
        assertEquals(
                List.of(new McaRegionFileInspector.Coordinate(64, 96)),
                McaRegionFileInspector.occupiedChunks(temporaryDirectory, region, 2, 3));

        Files.delete(sidecar);
        Files.write(temporaryDirectory.resolve("r.2.3.linear"), new byte[]{1});
        assertTrue(McaRegionFileInspector.hasAlternativeContainer(temporaryDirectory, 2, 3));
    }
}
