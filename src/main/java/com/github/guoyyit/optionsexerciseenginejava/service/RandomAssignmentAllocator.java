package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.exception.InvalidAssignmentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Component
public class RandomAssignmentAllocator implements AssignmentAllocator {
    private final long seed;

    public RandomAssignmentAllocator(@Value("${exercise.random-seed:20261003}") long seed) {
        this.seed = seed;
    }

    @Override
    public List<Assignment> allocate(Contract contract, List<Position> shortPositions, long exercisedQuantity, String exerciseId) {
        if (exercisedQuantity <= 0) {
            throw new InvalidAssignmentException("Exercised quantity must be positive: " + exercisedQuantity);
        }

        List<Position> writers = shortPositions.stream()
                .filter(position -> position.quantity() < 0)
                .sorted(Comparator.comparing(Position::accountId))
                .toList();
        long totalAvailable = writers.stream().mapToLong(position -> -position.quantity()).sum();
        if (writers.isEmpty() || totalAvailable < exercisedQuantity) {
            throw new InvalidAssignmentException("Insufficient short positions for exercise " + exerciseId);
        }

        long[] remaining = writers.stream().mapToLong(position -> -position.quantity()).toArray();
        long[] assigned = new long[writers.size()];
        Random random = new Random(seed);
        long remainingQuantity = exercisedQuantity;
        while (remainingQuantity > 0) {
            long pick = random.nextLong(totalAvailable);
            long cumulative = 0;
            for (int i = 0; i < remaining.length; i++) {
                cumulative += remaining[i];
                if (pick < cumulative) {
                    assigned[i]++;
                    remaining[i]--;
                    totalAvailable--;
                    remainingQuantity--;
                    break;
                }
            }
        }

        Instant now = Instant.now();
        List<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < writers.size(); i++) {
            if (assigned[i] > 0) {
                assignments.add(new Assignment(
                        UUID.randomUUID().toString(),
                        exerciseId,
                        contract.id(),
                        writers.get(i).accountId(),
                        assigned[i],
                        AssignmentStatus.PENDING,
                        now
                ));
        }
        }
        return assignments;
    }
}
