package net.enthusia.frontier.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure hysteretic policy for Frontier's aggregate generation budget. */
public final class GenerationShieldPolicy {
    private final List<GenerationShieldLevel> levels;
    private final Map<String, Integer> indexByName;
    private final double recoveryHysteresisMspt;

    public GenerationShieldPolicy(List<GenerationShieldLevel> levels, double recoveryHysteresisMspt) {
        Objects.requireNonNull(levels, "levels");
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("At least one generation-shield level is required");
        }
        if (!Double.isFinite(recoveryHysteresisMspt) || recoveryHysteresisMspt < 0.0) {
            throw new IllegalArgumentException("recoveryHysteresisMspt must be non-negative and finite");
        }
        List<GenerationShieldLevel> sorted = new ArrayList<>(levels);
        sorted.sort(Comparator.comparingDouble(GenerationShieldLevel::enterMspt));
        if (Double.compare(sorted.get(0).enterMspt(), 0.0) != 0) {
            throw new IllegalArgumentException("The first generation-shield level must enter at 0 MSPT");
        }
        Map<String, Integer> indexes = new HashMap<>();
        double previous = -1.0;
        for (int index = 0; index < sorted.size(); index++) {
            GenerationShieldLevel level = sorted.get(index);
            if (level.enterMspt() <= previous) {
                throw new IllegalArgumentException("Generation-shield thresholds must be strictly increasing");
            }
            if (indexes.put(level.name(), index) != null) {
                throw new IllegalArgumentException("Generation-shield level names must be unique: " + level.name());
            }
            previous = level.enterMspt();
        }
        this.levels = List.copyOf(sorted);
        this.indexByName = Map.copyOf(indexes);
        this.recoveryHysteresisMspt = recoveryHysteresisMspt;
    }

    public GenerationShieldLevel select(double mspt, GenerationShieldLevel current) {
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
            double threshold = levels.get(recoveredIndex).enterMspt() - recoveryHysteresisMspt;
            if (mspt >= threshold) {
                break;
            }
            recoveredIndex--;
        }
        return levels.get(recoveredIndex);
    }

    public List<GenerationShieldLevel> levels() {
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
