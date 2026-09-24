package net.enthusia.frontier.adapter.paper;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.enthusia.frontier.application.RegionReclaimResult;
import net.enthusia.frontier.application.StorageReclaimPort;
import net.enthusia.frontier.domain.ChunkKey;
import net.enthusia.frontier.domain.RegionKey;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Paper/Leaf 1.21.11 Moonrise storage adapter.
 *
 * <p>Logical deletion uses Moonrise's synchronized RegionDataController DELETE path.
 * Physical reclamation is deliberately narrower: only a fully empty MCA container is
 * unlinked, and only while holding the corresponding RegionFileStorage monitor after
 * proving the region is not in the live cache. Unknown formats and sidecars fail closed.
 */
public final class MoonriseStorageReclaimAdapter implements StorageReclaimPort {
    private static final String MOONRISE_IO =
            "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO";
    private static final String REGION_FILE_TYPE = MOONRISE_IO + "$RegionFileType";
    private static final String CONTROLLER = MOONRISE_IO + "$RegionDataController";
    private static final String WRITE_DATA = CONTROLLER + "$WriteData";
    private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final String COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";

    private final Method flushStorages;
    private final Method getControllerFor;
    private final Method startWrite;
    private final Method finishWrite;
    private final Method getCache;
    private final Method getRegionFileIfLoaded;
    private final StorageType[] storageTypes;

    private MoonriseStorageReclaimAdapter(
            Method flushStorages,
            Method getControllerFor,
            Method startWrite,
            Method finishWrite,
            Method getCache,
            Method getRegionFileIfLoaded,
            StorageType[] storageTypes) {
        this.flushStorages = flushStorages;
        this.getControllerFor = getControllerFor;
        this.startWrite = startWrite;
        this.finishWrite = finishWrite;
        this.getCache = getCache;
        this.getRegionFileIfLoaded = getRegionFileIfLoaded;
        this.storageTypes = storageTypes.clone();
    }

    public static MoonriseStorageReclaimAdapter create() throws ReflectiveOperationException {
        Class<?> moonriseIo = Class.forName(MOONRISE_IO);
        Class<?> regionFileType = Class.forName(REGION_FILE_TYPE);
        Class<?> controller = Class.forName(CONTROLLER);
        Class<?> writeData = Class.forName(WRITE_DATA);
        Class<?> serverLevel = Class.forName(SERVER_LEVEL);
        Class<?> compoundTag = Class.forName(COMPOUND_TAG);

        Method flushStorages = moonriseIo.getMethod("flushRegionStorages", serverLevel);
        Method getControllerFor = moonriseIo.getMethod("getControllerFor", serverLevel, regionFileType);
        Method startWrite = controller.getMethod("startWrite", int.class, int.class, compoundTag);
        Method finishWrite = controller.getMethod("finishWrite", int.class, int.class, writeData);
        Method getCache = controller.getMethod("getCache");
        Class<?> cacheType = getCache.getReturnType();
        Method getRegionFileIfLoaded = cacheType.getMethod(
                "moonrise$getRegionFileIfLoaded", int.class, int.class);

        StorageType[] types = {
            new StorageType(regionFileType.getField("CHUNK_DATA").get(null), "region"),
            new StorageType(regionFileType.getField("ENTITY_DATA").get(null), "entities"),
            new StorageType(regionFileType.getField("POI_DATA").get(null), "poi")
        };
        return new MoonriseStorageReclaimAdapter(
                flushStorages, getControllerFor, startWrite, finishWrite, getCache, getRegionFileIfLoaded, types);
    }

    @Override
    public String adapterName() {
        return "moonrise-mca-1.21.11";
    }

    @Override
    public boolean supportsPhysicalReclaim() {
        return true;
    }

    @Override
    public void clearChunk(String worldName, ChunkKey key) throws Exception {
        World world = requireWorld(worldName, key.worldUuid());
        Object level = getHandle(world);
        for (StorageType type : storageTypes) {
            Object controller = getControllerFor.invoke(null, level, type.token());
            Object writeData = startWrite.invoke(controller, new Object[]{key.x(), key.z(), null});
            finishWrite.invoke(controller, key.x(), key.z(), writeData);
        }
    }

    @Override
    public void flushWorld(String worldName, String expectedWorldUuid) throws Exception {
        World world = requireWorld(worldName, expectedWorldUuid);
        flushStorages.invoke(null, getHandle(world));
    }

    @Override
    public RegionReclaimResult reclaimEmptyRegion(String worldName, RegionKey region) throws Exception {
        World world = requireWorld(worldName, region.worldUuid());
        Object level = getHandle(world);
        boolean deletedAny = false;
        for (StorageType type : storageTypes) {
            Path folder = world.getWorldFolder().toPath().resolve(type.directory());
            if (McaRegionFileInspector.hasAlternativeContainer(folder, region.x(), region.z())) {
                return RegionReclaimResult.UNSUPPORTED;
            }
            Path regionFile = folder.resolve(McaRegionFileInspector.regionName(region.x(), region.z()));
            if (!Files.exists(regionFile, LinkOption.NOFOLLOW_LINKS)) {
                if (!McaRegionFileInspector.isCompletelyEmpty(folder, regionFile, region.x(), region.z())) {
                    return RegionReclaimResult.NOT_EMPTY;
                }
                continue;
            }

            Object controller = getControllerFor.invoke(null, level, type.token());
            Object cache = getCache.invoke(controller);
            synchronized (cache) {
                int representativeChunkX = region.x() << 5;
                int representativeChunkZ = region.z() << 5;
                if (getRegionFileIfLoaded.invoke(cache, representativeChunkX, representativeChunkZ) != null) {
                    return RegionReclaimResult.DEFERRED_OPEN;
                }
                if (!McaRegionFileInspector.isCompletelyEmpty(folder, regionFile, region.x(), region.z())) {
                    return RegionReclaimResult.NOT_EMPTY;
                }
                if (Files.deleteIfExists(regionFile)) {
                    deletedAny = true;
                }
            }
        }
        return deletedAny ? RegionReclaimResult.RECLAIMED : RegionReclaimResult.ABSENT;
    }

    @Override
    public boolean primaryChunkDataPresent(String worldName, ChunkKey key) throws Exception {
        World world = requireWorld(worldName, key.worldUuid());
        Path folder = world.getWorldFolder().toPath().resolve("region");
        RegionKey region = RegionKey.fromChunk(key);
        if (McaRegionFileInspector.hasAlternativeContainer(folder, region.x(), region.z())) {
            throw new IllegalStateException("unsupported alternative region container detected");
        }
        Path regionFile = folder.resolve(McaRegionFileInspector.regionName(region.x(), region.z()));
        return McaRegionFileInspector.containsChunk(regionFile, key.x(), key.z());
    }

    @Override
    public List<ChunkKey> occupiedChunks(String worldName, RegionKey region) throws Exception {
        World world = requireWorld(worldName, region.worldUuid());
        flushWorld(worldName, region.worldUuid());
        Set<ChunkKey> occupied = new LinkedHashSet<>();
        for (StorageType type : storageTypes) {
            Path folder = world.getWorldFolder().toPath().resolve(type.directory());
            if (McaRegionFileInspector.hasAlternativeContainer(folder, region.x(), region.z())) {
                throw new IllegalStateException("unsupported alternative region container detected");
            }
            Path regionFile = folder.resolve(McaRegionFileInspector.regionName(region.x(), region.z()));
            for (McaRegionFileInspector.Coordinate coordinate : McaRegionFileInspector.occupiedChunks(
                    folder, regionFile, region.x(), region.z())) {
                occupied.add(new ChunkKey(region.worldUuid(), coordinate.x(), coordinate.z()));
            }
        }
        return List.copyOf(occupied);
    }

    private static Object getHandle(World world) throws ReflectiveOperationException {
        Method getHandle = world.getClass().getMethod("getHandle");
        return getHandle.invoke(world);
    }

    private static World requireWorld(String worldName, String expectedUuid) {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(expectedUuid, "expectedUuid");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalStateException("managed world is not loaded: " + worldName);
        }
        UUID expected;
        try {
            expected = UUID.fromString(expectedUuid);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid expected world UUID", exception);
        }
        if (!world.getUID().equals(expected)) {
            throw new IllegalStateException("managed world UUID changed for " + worldName);
        }
        return world;
    }

    private record StorageType(Object token, String directory) {
        private StorageType {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(directory, "directory");
        }
    }
}
