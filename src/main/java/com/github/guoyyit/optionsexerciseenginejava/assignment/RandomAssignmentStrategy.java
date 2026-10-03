package com.github.guoyyit.optionsexerciseenginejava.assignment;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component("randomAssignmentStrategy")
public class RandomAssignmentStrategy implements AssignmentStrategy {

    private final EngineProperties properties;

    public RandomAssignmentStrategy(EngineProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "random";
    }

    @Override
    public List<Assignment> allocate(String contractCode, long exercisedQuantity,
                                     List<Position> shortPositions, long batchNonce) {
        List<Position> shorts = shortPositions.stream()
                .filter(Position::isShort)
                .sorted(Comparator.comparing(Position::getId))
                .toList();
        long totalShort = shorts.stream().mapToLong(Position::shortQuantity).sum();
        if (totalShort < exercisedQuantity) {
            throw new IllegalStateException(
                    "insufficient short interest for " + contractCode + ": need=" + exercisedQuantity
                            + ", available=" + totalShort);
        }
        long seed = properties.getRandomSeed()
                ^ batchNonce * 0x9E3779B97F4A7C15L
                ^ (long) contractCode.hashCode();
        Random random = new Random(seed);
        long[] remaining = new long[shorts.size()];
        for (int i = 0; i < shorts.size(); i++) {
            remaining[i] = shorts.get(i).shortQuantity();
        }
        long[] picked = new long[shorts.size()];
        long alive = totalShort;
        for (long unit = 0; unit < exercisedQuantity; unit++) {
            long draw = Math.floorMod(random.nextLong(), alive);
            long cursor = 0L;
            for (int i = 0; i < shorts.size(); i++) {
                if (remaining[i] == 0) {
                    continue;
                }
                if (draw < cursor + remaining[i]) {
                    picked[i]++;
                    remaining[i]--;
                    alive--;
                    break;
                }
                cursor += remaining[i];
            }
        }
        List<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < shorts.size(); i++) {
            if (picked[i] > 0) {
                assignments.add(new Assignment(shorts.get(i).getId(), shorts.get(i).getAccountId(),
                        contractCode, picked[i]));
            }
        }
        return assignments;
    }

    public Map<String, Long> distribution(List<Assignment> assignments) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            result.merge(assignment.shortPositionId(), assignment.quantity(), Long::sum);
        }
        return result;
    }
}
