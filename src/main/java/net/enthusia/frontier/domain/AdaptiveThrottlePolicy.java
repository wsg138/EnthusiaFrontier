package net.enthusia.frontier.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure hysteretic policy that maps recent MSPT to one generation throttle level. */
public final class AdaptiveThrottlePolicy {
    private final List<ThrottleLevel> levels;
    private final Map<String, Integer> indexByName;
    private final double recoveryHysteresisMspt;

    public AdaptiveThrottlePolicy(List<ThrottleLevel> levels, double recoveryHysteresisMspt) {
        Objects.requireNonNull(levels, "levels");
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("At least one throttle level is required");
        }
        if (!Double.isFinite(recoveryHysteresisMspt) || recoveryHysteresisMspt < 0.0) {
            throw new IllegalArgumentException("recoveryHysteresisMspt must be non-negative and finite");
        }

        List<ThrottleLevel> sorted = new ArrayList<>(levels);
        sorted.sort(Comparator.comparingDouble(ThrottleLevel::enterMspt));
        if (Double.compare(sorted.get(0).enterMspt(), 0.0) != 0) {
            throw new IllegalArgumentException("The first throttle level must enter at 0 MSPT");
        }

        Map<String, Integer> indexes = new HashMap<>();
        double previous = -1.0;
        for (int index = 0; index < sorted.size(); index++) {
            ThrottleLevel level = sorted.get(index);
            if (level.enterMspt() <= previous) {
                throw new IllegalArgumentException("Throttle enter thresholds must be strictly increasing");
            }
            if (indexes.put(level.name(), index) != null) {
                throw new IllegalArgumentException("Throttle level names must be unique: " + level.name());
            }
            previous = level.enterMspt();
        }

        this.levels = List.copyOf(sorted);
        this.indexByName = Map.copyOf(indexes);
        this.recoveryHysteresisMspt = recoveryHysteresisMspt;
    }

    public ThrottleLevel select(double mspt, ThrottleLevel current) {
        if (!Double.isFinite(mspt) || mspt < 0.0) {
            throw new IllegalArgumentException("mspt must be non-negative and finite");
        }

        int targetIndex = highestEnteredIndex(mspt);
        if (current == null) {
            return levels.get(targetIndex);
        }

        Integer currentIndexValue = indexByName.get(current.name());
        if (currentIndexValue == null) {
            return levels.get(targetIndex);
        }
        int currentIndex = currentIndexValue;

        if (targetIndex >= currentIndex) {
            return levels.get(targetIndex);
        }

        int recoveredIndex = currentIndex;
        while (recoveredIndex > targetIndex) {
            double recoveryThreshold = levels.get(recoveredIndex).enterMspt() - recoveryHysteresisMspt;
            if (mspt >= recoveryThreshold) {
                break;
            }
            recoveredIndex--;
        }
        return levels.get(recoveredIndex);
    }

    public List<ThrottleLevel> levels() {
        return levels;
    }

    private int highestEnteredIndex(double mspt) {
        int selected = 0;
        for (int index = 1; index < levels.size(); index++) {
            if (mspt < levels.get(index).enterMspt()) {
                break;
            }
            selected = index;
        }
        return selected;
    }
}
