package net.enthusia.frontier.adapter.paper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal fail-closed reader for the Anvil location table and external .mcc sidecars. */
public final class McaRegionFileInspector {
    static final int LOCATION_TABLE_BYTES = 4096;
    static final int HEADER_BYTES = 8192;
    private static final Pattern EXTERNAL_CHUNK = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");

    private McaRegionFileInspector() {
    }

    public static boolean containsChunk(Path regionFile, int chunkX, int chunkZ) throws IOException {
        if (!Files.exists(regionFile, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        ByteBuffer header = readLocationTable(regionFile);
        int index = (chunkX & 31) + ((chunkZ & 31) << 5);
        return header.getInt(index * Integer.BYTES) != 0;
    }

    public static List<Coordinate> occupiedChunks(
            Path folder, Path regionFile, int regionX, int regionZ) throws IOException {
        Set<Coordinate> occupied = new LinkedHashSet<>();
        if (Files.exists(regionFile, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer header = readLocationTable(regionFile);
            for (int index = 0; index < 1024; index++) {
                if (header.getInt(index * Integer.BYTES) == 0) {
                    continue;
                }
                int localX = index & 31;
                int localZ = index >> 5;
                occupied.add(new Coordinate((regionX << 5) + localX, (regionZ << 5) + localZ));
            }
        }
        occupied.addAll(externalChunkSidecars(folder, regionX, regionZ));
        return List.copyOf(occupied);
    }

    public static boolean isCompletelyEmpty(Path folder, Path regionFile, int regionX, int regionZ) throws IOException {
        return occupiedChunks(folder, regionFile, regionX, regionZ).isEmpty();
    }

    public static boolean hasAlternativeContainer(Path folder, int regionX, int regionZ) throws IOException {
        String mcaName = regionName(regionX, regionZ);
        String prefix = "r." + regionX + "." + regionZ + ".";
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, prefix + "*")) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName == null) {
                    throw new IOException("region directory entry has no filename");
                }
                if (!fileName.toString().equals(mcaName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String regionName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    private static ByteBuffer readLocationTable(Path regionFile) throws IOException {
        if (Files.isSymbolicLink(regionFile)
                || !Files.isRegularFile(regionFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(regionFile) < HEADER_BYTES) {
            throw new IOException("region container is not a valid regular MCA file");
        }
        ByteBuffer header = ByteBuffer.allocate(LOCATION_TABLE_BYTES).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) {
                    throw new IOException("truncated MCA location table");
                }
            }
        }
        header.flip();
        return header;
    }

    private static List<Coordinate> externalChunkSidecars(Path folder, int regionX, int regionZ) throws IOException {
        List<Coordinate> result = new ArrayList<>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return result;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, "c.*.mcc")) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName == null) {
                    throw new IOException("external chunk sidecar has no filename");
                }
                Matcher matcher = EXTERNAL_CHUNK.matcher(fileName.toString());
                if (!matcher.matches()) {
                    continue;
                }
                int chunkX;
                int chunkZ;
                try {
                    chunkX = Integer.parseInt(matcher.group(1));
                    chunkZ = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException exception) {
                    throw new IOException("invalid external chunk sidecar name", exception);
                }
                if ((chunkX >> 5) == regionX && (chunkZ >> 5) == regionZ) {
                    result.add(new Coordinate(chunkX, chunkZ));
                }
            }
        }
        return result;
    }

    public record Coordinate(int x, int z) {
    }
}
