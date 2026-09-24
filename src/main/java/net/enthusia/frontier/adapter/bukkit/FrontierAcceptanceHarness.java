package net.enthusia.frontier.adapter.bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import net.enthusia.frontier.application.FrontierMutation;
import net.enthusia.frontier.application.FrontierRepository;
import net.enthusia.frontier.application.FrontierTrackingService;
import net.enthusia.frontier.application.MutationJournal;
import net.enthusia.frontier.application.RegionReclaimResult;
import net.enthusia.frontier.application.StorageReclaimPort;
import net.enthusia.frontier.domain.ActivityKind;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.CoreBoundaryPolicy;
import net.enthusia.frontier.domain.RegionKey;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Destructive end-to-end proof used only by the isolated Sentinel real-Paper sandbox.
 * It is intentionally inaccessible on a normal server even to an operator with permission.
 */
public final class FrontierAcceptanceHarness {
    public static final String TOKEN = "I_UNDERSTAND_DISPOSABLE_WORLD";
    private static final String SENTINEL_MOTD = "Enthusia Sentinel isolated smoke test";
    private static final int BASE_TEST_REGION = 2048;
    private static final int REGION_GAP = 8;
    private static final int CENTER = 16;
    private static final int MAX_IDLE_POLLS = 200;

    private final JavaPlugin plugin;
    private final FrontierTrackingService tracking;
    private final FrontierRepository repository;
    private final MutationJournal journal;
    private final StorageReclaimPort storage;
    private final Path stateFile;

    public FrontierAcceptanceHarness(
            JavaPlugin plugin,
            FrontierTrackingService tracking,
            FrontierRepository repository,
            MutationJournal journal,
            StorageReclaimPort storage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tracking = Objects.requireNonNull(tracking, "tracking");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.stateFile = plugin.getDataFolder().toPath().resolve("acceptance-state.properties");
    }

    public boolean execute(CommandSender sender, String phase, String token) {
        if (!TOKEN.equals(token) || !isolatedSentinel(sender)) {
            sender.sendMessage("§cFrontier acceptance is available only inside the isolated Sentinel sandbox.");
            return true;
        }
        if (phase.equalsIgnoreCase("prepare")) {
            prepare(sender);
            return true;
        }
        if (phase.equalsIgnoreCase("verify")) {
            verify(sender);
            return true;
        }
        sender.sendMessage("§cUsage: /frontier acceptance <prepare|verify> <confirmation-token>");
        return true;
    }

    private boolean isolatedSentinel(CommandSender sender) {
        String motd = PlainTextComponentSerializer.plainText().serialize(plugin.getServer().motd());
        return sender instanceof ConsoleCommandSender
                && plugin.getServer().getOnlinePlayers().isEmpty()
                && plugin.getServer().getMaxPlayers() <= 2
                && "127.0.0.1".equals(plugin.getServer().getIp())
                && SENTINEL_MOTD.equals(motd);
    }

    private void prepare(CommandSender sender) {
        if (Files.exists(stateFile)) {
            fail(sender, "acceptance state already exists; refusing to overwrite it", null);
            return;
        }
        World world = firstManagedWorld();
        if (world == null) {
            fail(sender, "no configured managed world is loaded", null);
            return;
        }
        CoreBoundaryPolicy policy = tracking.worldPolicies().get(world.getName());
        int coreRegion = Math.floorDiv(policy.radiusBlocks() + 511, 512);
        int candidateRegionX = Math.max(BASE_TEST_REGION, coreRegion + 64);
        int candidateRegionZ = candidateRegionX + 17;
        int protectedRegionX = candidateRegionX + REGION_GAP;
        int protectedRegionZ = candidateRegionZ + REGION_GAP;
        int candidateX = (candidateRegionX << 5) + CENTER;
        int candidateZ = (candidateRegionZ << 5) + CENTER;
        int protectedX = (protectedRegionX << 5) + CENTER;
        int protectedZ = (protectedRegionZ << 5) + CENTER;
        if (!insideBorder(world, candidateX, candidateZ) || !insideBorder(world, protectedX, protectedZ)) {
            fail(sender, "acceptance coordinates fall outside the disposable world border", null);
            return;
        }

        sender.sendMessage("§6[EnthusiaFrontier] acceptance prepare dispatched");
        world.getChunkAtAsync(candidateX, candidateZ, true, candidate -> {
            tracking.recordGenerated(world.getName(), world.getUID(), candidateX, candidateZ);
            world.unloadChunk(candidateX, candidateZ, true);
            world.getChunkAtAsync(protectedX, protectedZ, true, protectedChunk -> {
                tracking.recordGenerated(world.getName(), world.getUID(), protectedX, protectedZ);
                int markerY = markerY(world, protectedChunk);
                int markerX = (protectedX << 4) + 8;
                int markerZ = (protectedZ << 4) + 8;
                world.getBlockAt(markerX, markerY, markerZ).setType(Material.DIAMOND_BLOCK, false);
                tracking.recordActivity(
                        world.getName(), world.getUID(), protectedX, protectedZ, ActivityKind.BLOCK_PLACE);
                world.unloadChunk(protectedX, protectedZ, true);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> prepareAfterSave(
                        sender, world, candidateRegionX, candidateRegionZ,
                        candidateX, candidateZ, protectedX, protectedZ,
                        markerX, markerY, markerZ), 40L);
            });
        });
    }

    private void prepareAfterSave(
            CommandSender sender,
            World world,
            int regionX,
            int regionZ,
            int candidateX,
            int candidateZ,
            int protectedX,
            int protectedZ,
            int markerX,
            int markerY,
            int markerZ) {
        try {
            storage.flushWorld(world.getName(), world.getUID().toString());
            RegionKey region = new RegionKey(world.getUID().toString(), regionX, regionZ);
            List<ChunkKey> occupied = storage.occupiedChunks(world.getName(), region);
            if (occupied.isEmpty()) {
                throw new IllegalStateException("generated acceptance region has no persisted storage");
            }
            for (ChunkKey key : occupied) {
                tracking.recordGenerated(world.getName(), world.getUID(), key.x(), key.z());
                if (world.isChunkLoaded(key.x(), key.z()) && !world.unloadChunk(key.x(), key.z(), true)) {
                    throw new IllegalStateException("acceptance region still has a loaded chunk");
                }
            }
            waitForIdle(sender, 0, () -> clearAcceptanceRegion(
                    sender, world, region, occupied, candidateX, candidateZ,
                    protectedX, protectedZ, markerX, markerY, markerZ));
        } catch (Exception exception) {
            fail(sender, "acceptance prepare failed", exception);
        }
    }

    private void clearAcceptanceRegion(
            CommandSender sender,
            World world,
            RegionKey region,
            List<ChunkKey> occupied,
            int candidateX,
            int candidateZ,
            int protectedX,
            int protectedZ,
            int markerX,
            int markerY,
            int markerZ) {
        try {
            long beforeBytes = regionBytes(world, region);
            for (ChunkKey key : occupied) {
                storage.clearChunk(world.getName(), key);
                if (!journal.submit(new FrontierMutation.Deleted(key, Instant.now()))) {
                    throw new IllegalStateException("ledger refused acceptance deletion marker");
                }
            }
            storage.flushWorld(world.getName(), world.getUID().toString());
            waitForIdle(sender, 0, () -> {
                try {
                    for (ChunkKey key : occupied) {
                        if (!repository.isDeleted(key)) {
                            throw new IllegalStateException("acceptance deletion marker was not durable");
                        }
                    }
                    ChunkKey protectedKey = new ChunkKey(world.getUID().toString(), protectedX, protectedZ);
                    if (!repository.isProtected(protectedKey)) {
                        throw new IllegalStateException("protected acceptance chunk was not durable");
                    }
                    long afterLogicalBytes = regionBytes(world, region);
                    Properties state = new Properties();
                    state.setProperty("world", world.getName());
                    state.setProperty("uuid", world.getUID().toString());
                    state.setProperty("region-x", Integer.toString(region.x()));
                    state.setProperty("region-z", Integer.toString(region.z()));
                    state.setProperty("candidate-x", Integer.toString(candidateX));
                    state.setProperty("candidate-z", Integer.toString(candidateZ));
                    state.setProperty("protected-x", Integer.toString(protectedX));
                    state.setProperty("protected-z", Integer.toString(protectedZ));
                    state.setProperty("marker-x", Integer.toString(markerX));
                    state.setProperty("marker-y", Integer.toString(markerY));
                    state.setProperty("marker-z", Integer.toString(markerZ));
                    state.setProperty("bytes-before", Long.toString(beforeBytes));
                    state.setProperty("bytes-after-logical", Long.toString(afterLogicalBytes));
                    writeState(state);
                    plugin.getLogger().info("FRONTIER_ACCEPTANCE_PREPARED region="
                            + region.x() + "," + region.z()
                            + " occupied=" + occupied.size()
                            + " bytesBefore=" + beforeBytes
                            + " bytesAfterLogical=" + afterLogicalBytes);
                } catch (Exception exception) {
                    fail(sender, "acceptance prepare durability verification failed", exception);
                }
            });
        } catch (Exception exception) {
            fail(sender, "acceptance logical reclaim failed", exception);
        }
    }

    private void verify(CommandSender sender) {
        Properties state;
        try {
            state = readState();
        } catch (Exception exception) {
            fail(sender, "acceptance state could not be read", exception);
            return;
        }
        World world = plugin.getServer().getWorld(state.getProperty("world", ""));
        if (world == null || !world.getUID().toString().equals(state.getProperty("uuid"))) {
            fail(sender, "acceptance world identity changed across restart", null);
            return;
        }
        waitForIdle(sender, 0, () -> verifyAfterIdle(sender, world, state));
    }

    private void verifyAfterIdle(CommandSender sender, World world, Properties state) {
        try {
            RegionKey region = new RegionKey(
                    world.getUID().toString(), integer(state, "region-x"), integer(state, "region-z"));
            ChunkKey candidate = new ChunkKey(
                    world.getUID().toString(), integer(state, "candidate-x"), integer(state, "candidate-z"));
            ChunkKey protectedKey = new ChunkKey(
                    world.getUID().toString(), integer(state, "protected-x"), integer(state, "protected-z"));
            if (!repository.isDeleted(candidate)) {
                throw new IllegalStateException("candidate deletion did not survive restart");
            }
            if (!repository.isProtected(protectedKey)) {
                throw new IllegalStateException("protected ledger state did not survive restart");
            }
            if (!storage.primaryChunkDataPresent(world.getName(), protectedKey)) {
                throw new IllegalStateException("protected chunk storage disappeared");
            }

            long beforePhysical = regionBytes(world, region);
            RegionReclaimResult reclaim = storage.reclaimEmptyRegion(world.getName(), region);
            long afterPhysical = regionBytes(world, region);
            long afterLogical = longValue(state, "bytes-after-logical");
            if (reclaim != RegionReclaimResult.RECLAIMED && reclaim != RegionReclaimResult.ABSENT) {
                throw new IllegalStateException("physical reclaim did not complete: " + reclaim);
            }
            if (afterLogical > 0L && afterPhysical >= beforePhysical) {
                throw new IllegalStateException("physical reclaim did not reduce allocated region-file bytes");
            }

            int markerX = integer(state, "marker-x");
            int markerY = integer(state, "marker-y");
            int markerZ = integer(state, "marker-z");
            world.getChunkAt(protectedKey.x(), protectedKey.z());
            if (world.getBlockAt(markerX, markerY, markerZ).getType() != Material.DIAMOND_BLOCK) {
                throw new IllegalStateException("protected marker block was not preserved");
            }
            world.unloadChunk(protectedKey.x(), protectedKey.z(), false);

            world.getChunkAtAsync(candidate.x(), candidate.z(), true, regenerated -> {
                world.unloadChunk(regenerated.getX(), regenerated.getZ(), true);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> finishRegenerationProof(
                        sender, world, candidate, beforePhysical, afterPhysical), 20L);
            });
        } catch (Exception exception) {
            fail(sender, "acceptance verification failed", exception);
        }
    }

    private void finishRegenerationProof(
            CommandSender sender, World world, ChunkKey candidate, long beforePhysical, long afterPhysical) {
        try {
            storage.flushWorld(world.getName(), world.getUID().toString());
            if (!storage.primaryChunkDataPresent(world.getName(), candidate)) {
                throw new IllegalStateException("deleted chunk did not regenerate and persist");
            }
            Files.deleteIfExists(stateFile);
            plugin.getLogger().info("FRONTIER_ACCEPTANCE_RECLAIM_OK bytesBeforePhysical="
                    + beforePhysical + " bytesAfterPhysical=" + afterPhysical);
            sender.sendMessage("§aFrontier real-server reclaim acceptance passed.");
        } catch (Exception exception) {
            fail(sender, "acceptance regeneration proof failed", exception);
        }
    }

    private void waitForIdle(CommandSender sender, int attempt, Runnable continuation) {
        if (journal.isIdle()) {
            continuation.run();
            return;
        }
        if (attempt >= MAX_IDLE_POLLS) {
            fail(sender, "ledger did not become idle during acceptance", null);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> waitForIdle(sender, attempt + 1, continuation), 1L);
    }

    private World firstManagedWorld() {
        for (String name : tracking.worldPolicies().keySet()) {
            World world = plugin.getServer().getWorld(name);
            if (world != null) {
                return world;
            }
        }
        return null;
    }

    private static boolean insideBorder(World world, int chunkX, int chunkZ) {
        Location location = new Location(world, (chunkX << 4) + 8.0, 64.0, (chunkZ << 4) + 8.0);
        return world.getWorldBorder().isInside(location);
    }

    private static int markerY(World world, Chunk chunk) {
        int blockX = (chunk.getX() << 4) + 8;
        int blockZ = (chunk.getZ() << 4) + 8;
        int highest = world.getHighestBlockYAt(blockX, blockZ) + 1;
        return Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 2, highest));
    }

    private static long regionBytes(World world, RegionKey region) throws IOException {
        long total = 0L;
        for (String directory : List.of("region", "entities", "poi")) {
            Path file = world.getWorldFolder().toPath().resolve(directory)
                    .resolve("r." + region.x() + "." + region.z() + ".mca");
            if (Files.isRegularFile(file)) {
                total = Math.addExact(total, Files.size(file));
            }
        }
        return total;
    }

    private void writeState(Properties state) throws IOException {
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(
                temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            state.store(output, "EnthusiaFrontier isolated acceptance state");
        }
        try {
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Properties readState() throws IOException {
        Properties state = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            state.load(input);
        }
        return state;
    }

    private static int integer(Properties state, String key) {
        return Integer.parseInt(required(state, key));
    }

    private static long longValue(Properties state, String key) {
        return Long.parseLong(required(state, key));
    }

    private static String required(Properties state, String key) {
        String value = state.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("acceptance state missing " + key);
        }
        return value;
    }

    private void fail(CommandSender sender, String message, Exception exception) {
        if (exception == null) {
            plugin.getLogger().severe("FRONTIER_ACCEPTANCE_FAILED " + message);
        } else {
            plugin.getLogger().log(Level.SEVERE, "FRONTIER_ACCEPTANCE_FAILED " + message, exception);
        }
        sender.sendMessage("§c" + message);
    }
}
