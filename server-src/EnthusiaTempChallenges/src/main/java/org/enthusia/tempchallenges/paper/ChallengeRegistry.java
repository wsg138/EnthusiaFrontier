package org.enthusia.tempchallenges.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.enthusia.tempchallenges.application.ChallengeRouter;
import org.enthusia.tempchallenges.domain.ChallengeDefinition;
import org.enthusia.tempchallenges.domain.SignalKey;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ChallengeRegistry {
    private final Map<String, ChallengeDefinition> byId;
    private final ChallengeRouter router;

    private ChallengeRegistry(Map<String, ChallengeDefinition> byId) {
        this.byId = Map.copyOf(byId);
        this.router = new ChallengeRouter(byId.values());
    }

    public static ChallengeRegistry load(FileConfiguration config) {
        ConfigurationSection root = Objects.requireNonNull(config.getConfigurationSection("challenges"),
                "config.yml missing challenges");
        Map<String, ChallengeDefinition> definitions = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = Objects.requireNonNull(root.getConfigurationSection(id));
            Set<SignalKey> signals = new LinkedHashSet<>();
            for (String raw : s.getStringList("signals")) signals.add(SignalKey.parse(raw));
            ChallengeDefinition definition = new ChallengeDefinition(id,
                    s.getString("title", id),
                    s.getString("announcement", s.getString("title", id)),
                    s.getString("permission", "enthusia.frontier.first." + id),
                    s.getString("tag", ""),
                    s.getString("advancement-node", ""),
                    Math.max(0, s.getInt("xp", 0)),
                    s.getBoolean("locked", false),
                    s.getString("lock-reason", "Locked by server policy."),
                    signals);
            definitions.put(id, definition);
        }
        return new ChallengeRegistry(definitions);
    }

    public Collection<ChallengeDefinition> all() { return byId.values(); }
    public ChallengeDefinition get(String id) { return byId.get(id); }
    public List<ChallengeDefinition> route(SignalKey signal) { return router.route(signal); }
}
