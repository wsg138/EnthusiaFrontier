package org.enthusia.tempchallenges.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tempchallenges.domain.SignalKey;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigContractTest {
    @Test void requiredFrontierFirstsAndAuthoritativeSignalsArePresent() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8));
        ChallengeRegistry registry = ChallengeRegistry.load(config);
        List<String> required = List.of("first_diamonds", "first_nether", "first_fortress", "first_ancient_debris",
                "first_netherite_armor", "first_stronghold", "first_mace", "first_end", "first_dragon",
                "first_elytra", "first_wither", "first_beacon");
        for (String id : required) assertNotNull(registry.get(id), id);
        assertTrue(registry.get("first_elytra").locked());
        assertFalse(registry.route(SignalKey.parse("ITEM_ACQUIRED:DIAMOND")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("WORLD_ENTRY:NETHER")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("VANILLA_ADVANCEMENT:minecraft:nether/find_fortress")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("ITEM_ACQUIRED:ANCIENT_DEBRIS")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("ARMOR_COMPLETE:NETHERITE")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("VANILLA_ADVANCEMENT:minecraft:story/follow_ender_eye")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("ITEM_ACQUIRED:MACE")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("WORLD_ENTRY:THE_END")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("ENTITY_FINAL_KILL:ENDER_DRAGON")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("ENTITY_FINAL_KILL:WITHER")).isEmpty());
        assertFalse(registry.route(SignalKey.parse("VANILLA_ADVANCEMENT:minecraft:nether/create_beacon")).isEmpty());
    }
}
