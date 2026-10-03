package com.github.guoyyit.optionsexerciseenginejava.assignment;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component("proportionalAssignmentStrategy")
public class ProportionalAssignmentStrategy implements AssignmentStrategy {

    @Override
    public String name() {
        return "proportional";
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
        List<Assignment> assignments = new ArrayList<>();
        long allocated = 0L;
        for (int i = 0; i < shorts.size(); i++) {
            Position position = shorts.get(i);
            long quantity = exercisedQuantity * position.shortQuantity() / totalShort;
            if (i == shorts.size() - 1) {
                quantity = exercisedQuantity - allocated;
            }
            allocated += quantity;
            if (quantity > 0) {
                assignments.add(new Assignment(position.getId(), position.getAccountId(),
                        contractCode, quantity));
            }
        }
        return assignments;
    }
}
