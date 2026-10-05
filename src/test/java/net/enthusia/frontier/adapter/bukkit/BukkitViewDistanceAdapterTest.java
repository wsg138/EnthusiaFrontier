package net.enthusia.frontier.adapter.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class BukkitViewDistanceAdapterTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000138");

    @Test
    void adaptiveApplyUsesStableMaximumClientCacheRadius() {
        Fixture fixture = new Fixture();

        fixture.adapter.apply(7);
        fixture.adapter.apply(12);

        assertEquals(
                List.of(new ApplyCall(7, 15), new ApplyCall(12, 15)),
                fixture.backend.applies);
        assertEquals(1, fixture.backend.captureCount);
    }

    @Test
    void restoreReturnsRawMoonriseIntentCapturedBeforeFirstApply() {
        Fixture fixture = new Fixture();

        fixture.adapter.apply(12);
        fixture.adapter.restore();

        assertEquals(
                List.of(new BukkitViewDistanceAdapter.OriginalDistances(-1, -1)),
                fixture.backend.restored);
    }

    @Test
    void adaptiveTargetCannotExceedStableClientCacheRadius() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.adapter.apply(16));
    }

    private record ApplyCall(int viewDistance, int clientCacheRadius) {
    }

    private static final class FakeBackend
            implements BukkitViewDistanceAdapter.PlayerDistanceBackend {
        private final List<ApplyCall> applies = new ArrayList<>();
        private final List<BukkitViewDistanceAdapter.OriginalDistances> restored =
                new ArrayList<>();
        private int captureCount;

        @Override
        public BukkitViewDistanceAdapter.OriginalDistances capture(Player player) {
            captureCount++;
            return new BukkitViewDistanceAdapter.OriginalDistances(-1, -1);
        }

        @Override
        public void apply(Player player, int viewDistance, int clientCacheRadius) {
            applies.add(new ApplyCall(viewDistance, clientCacheRadius));
        }

        @Override
        public void restore(
                Player player,
                BukkitViewDistanceAdapter.OriginalDistances original) {
            restored.add(original);
        }

        @Override
        public void forget(UUID playerId) {
        }

        @Override
        public void resetAdvertisement(UUID playerId) {
        }
    }

    private static final class Fixture {
        private final JavaPlugin plugin = mock(JavaPlugin.class);
        private final Server server = mock(Server.class);
        private final Player player = mock(Player.class);
        private final FakeBackend backend = new FakeBackend();
        private final BukkitViewDistanceAdapter adapter;

        private Fixture() {
            when(plugin.getServer()).thenReturn(server);
            doReturn(List.of(player)).when(server).getOnlinePlayers();
            when(player.getUniqueId()).thenReturn(PLAYER_ID);
            adapter = new BukkitViewDistanceAdapter(plugin, backend, 15);
        }
    }
}
