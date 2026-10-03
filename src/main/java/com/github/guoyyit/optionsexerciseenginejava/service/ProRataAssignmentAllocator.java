package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.exception.InvalidAssignmentException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class ProRataAssignmentAllocator implements AssignmentAllocator {
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

        record Quota(Position writer, long floor, long numerator, long denominator) {
        }
        List<Quota> quotas = new ArrayList<>();
        long allocated = 0;
        for (Position writer : writers) {
            long available = -writer.quantity();
            long product = Math.multiplyExact(exercisedQuantity, available);
            long floor = Math.floorDiv(product, totalAvailable);
            long remainder = product - floor * totalAvailable;
            quotas.add(new Quota(writer, floor, remainder, totalAvailable));
            allocated += floor;
        }

        long remaining = exercisedQuantity - allocated;
        quotas.sort(Comparator
                .comparingLong((Quota quota) -> quota.numerator())
                .reversed()
                .thenComparing(quota -> quota.writer().accountId()));
        for (int i = 0; i < remaining; i++) {
            Quota quota = quotas.get(i);
            quotas.set(i, new Quota(quota.writer(), quota.floor() + 1, quota.numerator(), quota.denominator()));
        }

        Instant now = Instant.now();
        List<Assignment> assignments = new ArrayList<>();
        for (Quota quota : quotas) {
            if (quota.floor() > 0) {
                assignments.add(new Assignment(
                        UUID.randomUUID().toString(),
                        exerciseId,
                        contract.id(),
                        quota.writer().accountId(),
                        quota.floor(),
                        AssignmentStatus.PENDING,
                        now
                ));
            }
        }
        return assignments;
    }
}
