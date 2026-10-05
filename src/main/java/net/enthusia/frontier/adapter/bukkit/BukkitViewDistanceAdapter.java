package net.enthusia.frontier.adapter.bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.enthusia.frontier.application.ViewDistancePort;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies Frontier's adaptive per-player view distance while keeping the
 * client-advertised chunk-cache radius stable.
 *
 * <p>Moonrise normally advertises every effective send-distance change with a
 * {@code ClientboundSetChunkCacheRadiusPacket}. The vanilla client rebuilds its
 * chunk-cache storage around that new radius, which is visible as a whole-view
 * flash. Frontier instead advertises the maximum configured radius once and
 * lets Moonrise add/remove individual chunks inside that stable client cache as
 * the adaptive loading/send radius changes.</p>
 */
public final class BukkitViewDistanceAdapter implements ViewDistancePort, Listener {
    private final JavaPlugin plugin;
    private final Server server;
    private final PlayerDistanceBackend backend;
    private final int clientCacheRadius;
    private final Map<UUID, OriginalDistances> originals = new HashMap<>();
    private int targetViewDistance = -1;

    public static BukkitViewDistanceAdapter create(JavaPlugin plugin, int clientCacheRadius)
            throws ReflectiveOperationException {
        return new BukkitViewDistanceAdapter(
                plugin,
                MoonrisePlayerDistanceBackend.create(),
                clientCacheRadius);
    }

    BukkitViewDistanceAdapter(
            JavaPlugin plugin,
            PlayerDistanceBackend backend,
            int clientCacheRadius) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.backend = Objects.requireNonNull(backend, "backend");
        if (clientCacheRadius < 2 || clientCacheRadius > 32) {
            throw new IllegalArgumentException("clientCacheRadius must be within 2..32 chunks");
        }
        this.clientCacheRadius = clientCacheRadius;
    }

    @Override
    public void apply(int viewDistance) {
        if (viewDistance < 2 || viewDistance > clientCacheRadius) {
            throw new IllegalArgumentException(
                    "viewDistance must be within 2.." + clientCacheRadius + " chunks");
        }
        targetViewDistance = viewDistance;
        for (Player player : server.getOnlinePlayers()) {
            applyTo(player);
        }
    }

    @Override
    public void restore() {
        targetViewDistance = -1;
        for (Player player : server.getOnlinePlayers()) {
            OriginalDistances original = originals.remove(player.getUniqueId());
            if (original != null) {
                backend.restore(player, original);
            }
        }
        originals.clear();
    }

    public int targetViewDistance() {
        return targetViewDistance;
    }

    public int clientCacheRadius() {
        return clientCacheRadius;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleApply(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        scheduleApply(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleApply(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        originals.remove(player.getUniqueId());
        backend.forget(player.getUniqueId());
    }

    private void scheduleApply(Player player, boolean reannounceClientRadius) {
        if (reannounceClientRadius) {
            backend.resetAdvertisement(player.getUniqueId());
        }
        server.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && targetViewDistance >= 2) {
                applyTo(player);
            }
        });
    }

    private void applyTo(Player player) {
        remember(player);
        backend.apply(player, targetViewDistance, clientCacheRadius);
    }

    private void remember(Player player) {
        originals.computeIfAbsent(
                player.getUniqueId(),
                ignored -> backend.capture(player));
    }

    interface PlayerDistanceBackend {
        OriginalDistances capture(Player player);

        void apply(Player player, int viewDistance, int clientCacheRadius);

        void restore(Player player, OriginalDistances original);

        void forget(UUID playerId);

        void resetAdvertisement(UUID playerId);
    }

    record OriginalDistances(int rawLoadDistance, int rawSendDistance) {
    }

    private static final class MoonrisePlayerDistanceBackend implements PlayerDistanceBackend {
        private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.entity.CraftPlayer";
        private static final String SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";
        private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
        private static final String PLAYER_PATCH =
                "ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer";
        private static final String HOLDER =
                "ca.spottedleaf.moonrise.patches.chunk_system.player."
                        + "RegionizedPlayerChunkLoader$ViewDistanceHolder";
        private static final String DISTANCES =
                "ca.spottedleaf.moonrise.patches.chunk_system.player."
                        + "RegionizedPlayerChunkLoader$ViewDistances";
        private static final String LOADER =
                "ca.spottedleaf.moonrise.patches.chunk_system.player."
                        + "RegionizedPlayerChunkLoader$PlayerChunkLoaderData";
        private static final String PLATFORM_HOOKS =
                "ca.spottedleaf.moonrise.common.PlatformHooks";
        private static final String PACKET =
                "net.minecraft.network.protocol.Packet";
        private static final String CACHE_RADIUS_PACKET =
                "net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket";
        private static final String CONNECTION =
                "net.minecraft.server.network.ServerGamePacketListenerImpl";

        private final Method craftPlayerGetHandle;
        private final Method playerHolderGetter;
        private final Method playerLoaderGetter;
        private final Method distancesGetter;
        private final Method loadGetter;
        private final Method sendGetter;
        private final Method loadSetter;
        private final Method sendSetter;
        private final Method levelGetter;
        private final Method updateMaps;
        private final Object platformHooks;
        private final VarHandle lastSentChunkRadius;
        private final Field connectionField;
        private final Constructor<?> cacheRadiusPacketConstructor;
        private final Method sendPacket;
        private final Set<UUID> advertisedPlayers = new HashSet<>();

        private MoonrisePlayerDistanceBackend(
                Method craftPlayerGetHandle,
                Method playerHolderGetter,
                Method playerLoaderGetter,
                Method distancesGetter,
                Method loadGetter,
                Method sendGetter,
                Method loadSetter,
                Method sendSetter,
                Method levelGetter,
                Method updateMaps,
                Object platformHooks,
                VarHandle lastSentChunkRadius,
                Field connectionField,
                Constructor<?> cacheRadiusPacketConstructor,
                Method sendPacket) {
            this.craftPlayerGetHandle = craftPlayerGetHandle;
            this.playerHolderGetter = playerHolderGetter;
            this.playerLoaderGetter = playerLoaderGetter;
            this.distancesGetter = distancesGetter;
            this.loadGetter = loadGetter;
            this.sendGetter = sendGetter;
            this.loadSetter = loadSetter;
            this.sendSetter = sendSetter;
            this.levelGetter = levelGetter;
            this.updateMaps = updateMaps;
            this.platformHooks = platformHooks;
            this.lastSentChunkRadius = lastSentChunkRadius;
            this.connectionField = connectionField;
            this.cacheRadiusPacketConstructor = cacheRadiusPacketConstructor;
            this.sendPacket = sendPacket;
        }

        static MoonrisePlayerDistanceBackend create() throws ReflectiveOperationException {
            Class<?> craftPlayer = Class.forName(CRAFT_PLAYER);
            Class<?> serverPlayer = Class.forName(SERVER_PLAYER);
            Class<?> serverLevel = Class.forName(SERVER_LEVEL);
            Class<?> playerPatch = Class.forName(PLAYER_PATCH);
            Class<?> holder = Class.forName(HOLDER);
            Class<?> distances = Class.forName(DISTANCES);
            Class<?> loader = Class.forName(LOADER);
            Class<?> hooks = Class.forName(PLATFORM_HOOKS);
            Class<?> packet = Class.forName(PACKET);
            Class<?> cacheRadiusPacket = Class.forName(CACHE_RADIUS_PACKET);
            Class<?> connection = Class.forName(CONNECTION);

            Method hooksGetter = hooks.getMethod("get");
            Object platformHooks = hooksGetter.invoke(null);
            if (platformHooks == null) {
                throw new IllegalStateException("Moonrise PlatformHooks.get() returned null");
            }

            VarHandle lastSentChunkRadius = MethodHandles.privateLookupIn(
                    loader, MethodHandles.lookup())
                    .findVarHandle(loader, "lastSentChunkRadius", int.class);

            return new MoonrisePlayerDistanceBackend(
                    craftPlayer.getMethod("getHandle"),
                    playerPatch.getMethod("moonrise$getViewDistanceHolder"),
                    playerPatch.getMethod("moonrise$getChunkLoader"),
                    holder.getMethod("getViewDistances"),
                    distances.getMethod("loadViewDistance"),
                    distances.getMethod("sendViewDistance"),
                    holder.getMethod("setLoadViewDistance", int.class),
                    holder.getMethod("setSendViewDistance", int.class),
                    serverPlayer.getMethod("level"),
                    hooks.getMethod("updateMaps", serverLevel, serverPlayer),
                    platformHooks,
                    lastSentChunkRadius,
                    serverPlayer.getField("connection"),
                    cacheRadiusPacket.getConstructor(int.class),
                    connection.getMethod("send", packet));
        }

        @Override
        public OriginalDistances capture(Player player) {
            Object holder = holder(handle(player));
            Object distances = invoke(distancesGetter, holder);
            return new OriginalDistances(
                    invokeInt(loadGetter, distances),
                    invokeInt(sendGetter, distances));
        }

        @Override
        public void apply(Player player, int viewDistance, int clientCacheRadius) {
            Object handle = handle(player);
            Object holder = holder(handle);
            Object loader = invoke(playerLoaderGetter, handle);
            if (loader == null) {
                throw new IllegalStateException("Moonrise player chunk loader is not initialized");
            }

            // Moonrise's raw load radius is API view distance + 1. Keep its raw
            // send ceiling at Frontier's maximum while the effective send radius
            // follows the adaptive load radius.
            invokeVoid(loadSetter, holder, viewDistance + 1);
            invokeVoid(sendSetter, holder, clientCacheRadius);

            // updateMaps normally sends a cache-radius packet whenever effective
            // send distance changes. Pre-mark that effective radius as already
            // advertised so Moonrise still diffs/unloads/sends individual chunks
            // without resizing the vanilla client's chunk-cache storage.
            setInt(lastSentChunkRadius, loader, viewDistance);
            Object level = invoke(levelGetter, handle);
            invokeVoid(updateMaps, platformHooks, level, handle);

            UUID playerId = player.getUniqueId();
            if (advertisedPlayers.add(playerId)) {
                sendClientCacheRadius(handle, clientCacheRadius);
            }
        }

        @Override
        public void restore(Player player, OriginalDistances original) {
            Object handle = handle(player);
            Object holder = holder(handle);
            Object loader = invoke(playerLoaderGetter, handle);
            if (loader == null) {
                advertisedPlayers.remove(player.getUniqueId());
                return;
            }

            invokeVoid(loadSetter, holder, original.rawLoadDistance());
            invokeVoid(sendSetter, holder, original.rawSendDistance());

            // Force Moonrise to announce the real restored effective radius so
            // Frontier leaves no client-side cache override behind on disable.
            setInt(lastSentChunkRadius, loader, Integer.MIN_VALUE);
            Object level = invoke(levelGetter, handle);
            invokeVoid(updateMaps, platformHooks, level, handle);
            advertisedPlayers.remove(player.getUniqueId());
        }

        @Override
        public void forget(UUID playerId) {
            advertisedPlayers.remove(playerId);
        }

        @Override
        public void resetAdvertisement(UUID playerId) {
            advertisedPlayers.remove(playerId);
        }

        private Object handle(Player player) {
            if (!craftPlayerGetHandle.getDeclaringClass().isInstance(player)) {
                throw new IllegalStateException(
                        "Expected CraftPlayer but got " + player.getClass().getName());
            }
            return invoke(craftPlayerGetHandle, player);
        }

        private Object holder(Object handle) {
            Object holder = invoke(playerHolderGetter, handle);
            if (holder == null) {
                throw new IllegalStateException("Moonrise view-distance holder is not initialized");
            }
            return holder;
        }

        private void sendClientCacheRadius(Object handle, int radius) {
            try {
                Object connection = connectionField.get(handle);
                if (connection == null) {
                    throw new IllegalStateException("player connection is not initialized");
                }
                Object packet = cacheRadiusPacketConstructor.newInstance(radius);
                sendPacket.invoke(connection, packet);
            } catch (ReflectiveOperationException exception) {
                throw reflectionFailure("send stable client chunk-cache radius", exception);
            }
        }

        private static Object invoke(Method method, Object owner, Object... arguments) {
            try {
                return method.invoke(owner, arguments);
            } catch (ReflectiveOperationException exception) {
                throw reflectionFailure(method.getName(), exception);
            }
        }

        private static int invokeInt(Method method, Object owner, Object... arguments) {
            Object value = invoke(method, owner, arguments);
            if (!(value instanceof Integer integer)) {
                throw new IllegalStateException(method.getName() + " did not return int");
            }
            return integer.intValue();
        }

        private static void invokeVoid(Method method, Object owner, Object... arguments) {
            invoke(method, owner, arguments);
        }

        private static void setInt(VarHandle handle, Object owner, int value) {
            handle.set(owner, value);
        }

        private static IllegalStateException reflectionFailure(
                String operation,
                ReflectiveOperationException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null
                    ? invocation.getCause()
                    : exception;
            return new IllegalStateException(
                    "Moonrise no-flash view-distance operation failed: " + operation,
                    cause);
        }
    }
}
