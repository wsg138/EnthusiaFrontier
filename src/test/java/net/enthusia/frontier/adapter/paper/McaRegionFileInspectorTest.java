package net.enthusia.frontier.adapter.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.enthusia.frontier.domain.RegionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McaRegionFileInspectorTest {
    private static final String WORLD = "world";
    @TempDir
    Path temporaryDirectory;

    @Test
    void batchScreenSkipsAbsentAndOccupiedRegionsAndKeepsEmptyCandidates() throws Exception {
        var absent = new RegionKey(WORLD, 0, 0);
        var empty = new RegionKey(WORLD, -1, -1);
        var occupied = new RegionKey(WORLD, 1, 1);
        Path region = Files.createDirectory(temporaryDirectory.resolve("region"));
        Files.write(region.resolve("r.-1.-1.mca"), new byte[8192]);
        byte[] header = new byte[8192];
        header[3] = 1;
        Files.write(region.resolve("r.1.1.mca"), header);
        var batch = List.of(absent, empty, occupied);
        assertEquals(List.of(empty), McaRegionFileInspector.reclaimCandidates(temporaryDirectory, batch));
        Files.write(region.resolve("c.-1.-1.mcc"), new byte[]{1});
        assertTrue(McaRegionFileInspector.reclaimCandidates(temporaryDirectory, batch).isEmpty());
        // Unknown containers must reach the existing unsupported-format handling.
        Files.write(region.resolve("r.0.0.other"), new byte[]{1});
        assertEquals(List.of(absent), McaRegionFileInspector.reclaimCandidates(temporaryDirectory, batch));
    }

    @Test
    void liveCheckDetectsChangesAfterDiscovery() throws Exception {
        var region = new RegionKey(WORLD, 0, 0);
        Path folder = Files.createDirectory(temporaryDirectory.resolve("region"));
        Path file = folder.resolve("r.0.0.mca");
        byte[] header = new byte[McaRegionFileInspector.HEADER_BYTES];
        Files.write(file, header);
        assertEquals(List.of(region), McaRegionFileInspector.reclaimCandidates(temporaryDirectory, List.of(region)));
        assertEquals(McaRegionFileInspector.Contents.EMPTY, McaRegionFileInspector.inspectForReclaim(folder, region));
        header[3] = 1;
        Files.write(file, header);
        assertEquals(McaRegionFileInspector.Contents.OCCUPIED, McaRegionFileInspector.inspectForReclaim(folder, region));
        Files.delete(file);
        assertEquals(McaRegionFileInspector.Contents.ABSENT, McaRegionFileInspector.inspectForReclaim(folder, region));
        Files.createFile(folder.resolve("c.31.31.mcc"));
        assertEquals(McaRegionFileInspector.Contents.OCCUPIED, McaRegionFileInspector.inspectForReclaim(folder, region));
        Files.createFile(folder.resolve("r.0.0.custom"));
        assertEquals(McaRegionFileInspector.Contents.UNSUPPORTED, McaRegionFileInspector.inspectForReclaim(folder, region));
    }

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
