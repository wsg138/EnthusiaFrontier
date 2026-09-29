package net.enthusia.frontier.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure hysteretic policy that maps MSPT to a configured player view-distance level. */
public final class AdaptiveViewDistancePolicy {
    private final List<ViewDistanceLevel> levels;
    private final Map<String, Integer> indexByName;
    private final double recoveryHysteresisMspt;

    public AdaptiveViewDistancePolicy(List<ViewDistanceLevel> levels, double recoveryHysteresisMspt) {
        Objects.requireNonNull(levels, "levels");
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("At least one view-distance level is required");
        }
        if (!Double.isFinite(recoveryHysteresisMspt) || recoveryHysteresisMspt < 0.0) {
            throw new IllegalArgumentException("recoveryHysteresisMspt must be non-negative and finite");
        }

        List<ViewDistanceLevel> sorted = new ArrayList<>(levels);
        sorted.sort(Comparator.comparingDouble(ViewDistanceLevel::enterMspt));
        if (Double.compare(sorted.get(0).enterMspt(), 0.0) != 0) {
            throw new IllegalArgumentException("The first view-distance level must enter at 0 MSPT");
        }

        Map<String, Integer> indexes = new HashMap<>();
        double previousThreshold = -1.0;
        int previousDistance = Integer.MAX_VALUE;
        for (int index = 0; index < sorted.size(); index++) {
            ViewDistanceLevel level = sorted.get(index);
            if (level.enterMspt() <= previousThreshold) {
                throw new IllegalArgumentException("View-distance MSPT thresholds must be strictly increasing");
            }
            if (level.viewDistance() > previousDistance) {
                throw new IllegalArgumentException("View distance must not increase as MSPT pressure rises");
            }
            if (indexes.put(level.name(), index) != null) {
                throw new IllegalArgumentException("View-distance level names must be unique: " + level.name());
            }
            previousThreshold = level.enterMspt();
            previousDistance = level.viewDistance();
        }

        this.levels = List.copyOf(sorted);
        this.indexByName = Map.copyOf(indexes);
        this.recoveryHysteresisMspt = recoveryHysteresisMspt;
    }

    public ViewDistanceLevel select(double mspt, ViewDistanceLevel current) {
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

    public int indexOf(ViewDistanceLevel level) {
        Objects.requireNonNull(level, "level");
        return indexByName.getOrDefault(level.name(), -1);
    }

    public List<ViewDistanceLevel> levels() {
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
