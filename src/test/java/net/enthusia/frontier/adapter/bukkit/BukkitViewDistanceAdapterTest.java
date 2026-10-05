package net.enthusia.frontier.adapter.bukkit;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void adaptiveApplyChangesOnlyLoadingViewDistance() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(List.of(player));
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getViewDistance()).thenReturn(8);

        BukkitViewDistanceAdapter adapter = new BukkitViewDistanceAdapter(plugin);
        adapter.apply(12);

        verify(player).setViewDistance(12);
        verify(player, never()).getSendViewDistance();
        verify(player, never()).setSendViewDistance(anyInt());
    }

    @Test
    void restoreDoesNotOverwriteInheritedSendViewDistance() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(List.of(player));
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getViewDistance()).thenReturn(8, 8, 12);

        BukkitViewDistanceAdapter adapter = new BukkitViewDistanceAdapter(plugin);
        adapter.apply(12);
        adapter.restore();

        verify(player).setViewDistance(12);
        verify(player).setViewDistance(8);
        verify(player, never()).getSendViewDistance();
        verify(player, never()).setSendViewDistance(anyInt());
    }
}
