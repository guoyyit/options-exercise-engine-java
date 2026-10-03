package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditLog;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import com.github.guoyyit.optionsexerciseenginejava.exception.InsufficientPositionException;
import com.github.guoyyit.optionsexerciseenginejava.exception.InvalidAssignmentException;
import com.github.guoyyit.optionsexerciseenginejava.store.StateManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SettlementService {
    public static final String CASH_INSTRUMENT = "CASH";

    private final StateManager stateManager;

    public SettlementService(StateManager stateManager) {
        this.stateManager = stateManager;
    }

    public SettlementResult settle(
            Contract contract,
            ExerciseInstruction exercise,
            List<Assignment> assignments,
            SettlementPrice settlementPrice
    ) {
        return settleBatch(contract, List.of(exercise), Map.of(exercise.id(), assignments), settlementPrice).get(0);
    }

    public List<SettlementResult> settleBatch(
            Contract contract,
            List<ExerciseInstruction> exercises,
            Map<String, List<Assignment>> assignmentsByExercise,
            SettlementPrice settlementPrice
    ) {
        return settleBatch(contract, exercises, assignmentsByExercise, settlementPrice, List.of(), List.of());
    }

    public List<SettlementResult> settleBatch(
            Contract contract,
            List<ExerciseInstruction> exercises,
            Map<String, List<Assignment>> assignmentsByExercise,
            SettlementPrice settlementPrice,
            List<AuditLog> extraAuditLogs,
            List<ExerciseInstruction> abandonedExercises
    ) {
        validateBatch(contract, exercises, assignmentsByExercise, settlementPrice, abandonedExercises);

        Map<String, SettlementResult> resultMap = new HashMap<>();
        stateManager.commit(current -> {
        Map<String, Position> positions = new HashMap<>();
        current.positions().forEach(position -> positions.put(key(position.accountId(), position.instrumentId()), position));
        Map<String, ExerciseInstruction> settledExercises = new HashMap<>();
        current.exercises().forEach(item -> settledExercises.put(item.id(), item));
        Map<String, Assignment> settledAssignments = new HashMap<>();
        current.assignments().forEach(item -> settledAssignments.put(item.id(), item));
        List<AuditLog> auditLogs = new ArrayList<>(current.auditLogs());
        auditLogs.addAll(extraAuditLogs);
        for (ExerciseInstruction abandoned : abandonedExercises) {
            if (abandoned.status() != ExerciseStatus.ABANDONED) {
                throw new InvalidAssignmentException("Terminal exercise is not abandoned: " + abandoned.id());
            }
            settledExercises.put(abandoned.id(), abandoned);
        }

        for (ExerciseInstruction exercise : exercises) {
            updateLongOptionPosition(positions, contract, exercise);
        }
        List<Assignment> allAssignments = new ArrayList<>();
        for (ExerciseInstruction exercise : exercises) {
            List<Assignment> assignments = assignmentsByExercise.get(exercise.id());
            allAssignments.addAll(assignments);
            for (Assignment assignment : assignments) {
                updateWriterOptionPosition(positions, contract, assignment);
            }
        }

        for (ExerciseInstruction exercise : exercises) {
            List<Assignment> assignments = assignmentsByExercise.get(exercise.id());
            if (contract.optionType() == OptionType.CALL) {
                settleCall(positions, contract, exercise, assignments);
            } else {
                settlePut(positions, contract, exercise, assignments);
            }
        }

        Instant now = Instant.now();
        for (ExerciseInstruction exercise : exercises) {
            ExerciseInstruction settledExercise = new ExerciseInstruction(
                    exercise.id(),
                    exercise.contractId(),
                    exercise.accountId(),
                    exercise.quantity(),
                    exercise.exerciseStyle(),
                    ExerciseStatus.SETTLED,
                    exercise.source(),
                    exercise.reason(),
                    exercise.idempotencyKey(),
                    exercise.createdAt()
            );
            settledExercises.put(settledExercise.id(), settledExercise);

            List<Assignment> assignments = assignmentsByExercise.get(exercise.id());
            List<Assignment> resultAssignments = new ArrayList<>();
            for (Assignment assignment : assignments) {
                Assignment settledAssignment = new Assignment(
                        assignment.id(),
                        assignment.exerciseId(),
                        assignment.contractId(),
                        assignment.writerAccountId(),
                        assignment.quantity(),
                        AssignmentStatus.SETTLED,
                        assignment.createdAt()
                );
                settledAssignments.put(settledAssignment.id(), settledAssignment);
                resultAssignments.add(settledAssignment);
            }

            auditLogs.add(new AuditLog(
                    UUID.randomUUID().toString(),
                    AuditAction.SETTLE,
                    "SYSTEM",
                    exercise.id(),
                    "Settled exercise " + exercise.id() + " at settlement price " + settlementPrice.price(),
                    now
            ));
            resultMap.put(exercise.id(), new SettlementResult(settledExercise, resultAssignments));
        }

        return new StateSnapshot(
                current.contracts(),
                List.copyOf(positions.values()),
                current.prices(),
                List.copyOf(settledExercises.values()),
                List.copyOf(settledAssignments.values()),
                List.copyOf(auditLogs)
        );
        });
        return exercises.stream().map(exercise -> resultMap.get(exercise.id())).toList();
    }

    private void validateBatch(
            Contract contract,
            List<ExerciseInstruction> exercises,
            Map<String, List<Assignment>> assignmentsByExercise,
            SettlementPrice settlementPrice,
            List<ExerciseInstruction> abandonedExercises
    ) {
        if (exercises == null || exercises.isEmpty()) {
            throw new InvalidAssignmentException("At least one exercise is required");
        }
        if (settlementPrice == null || !contract.id().equals(settlementPrice.contractId())) {
            throw new InvalidAssignmentException("Settlement price is required for contract " + contract.id());
        }

        Set<String> exerciseIds = new HashSet<>();
        Set<String> assignmentIds = new HashSet<>();
        Set<String> abandonedIds = abandonedExercises.stream().map(ExerciseInstruction::id).collect(java.util.stream.Collectors.toSet());
        for (ExerciseInstruction exercise : exercises) {
            if (abandonedIds.contains(exercise.id())) {
                throw new InvalidAssignmentException("Exercise cannot be both settled and abandoned: " + exercise.id());
            }
            if (!exerciseIds.add(exercise.id())) {
                throw new InvalidAssignmentException("Duplicate exercise in batch: " + exercise.id());
            }
            if (!contract.id().equals(exercise.contractId())) {
                throw new InvalidAssignmentException("Exercise contract does not match contract");
            }
            if (exercise.status() != ExerciseStatus.PENDING) {
                throw new InvalidAssignmentException("Exercise is not pending: " + exercise.id());
            }

            List<Assignment> assignments = assignmentsByExercise.get(exercise.id());
            if (assignments == null || assignments.isEmpty()) {
                throw new InvalidAssignmentException("Assignments are required for exercise " + exercise.id());
            }

            long assignedQuantity = 0;
            for (Assignment assignment : assignments) {
                if (!assignmentIds.add(assignment.id())) {
                    throw new InvalidAssignmentException("Duplicate assignment in batch: " + assignment.id());
                }
                if (!exercise.id().equals(assignment.exerciseId())) {
                    throw new InvalidAssignmentException("Assignment does not belong to exercise " + exercise.id());
                }
                if (!contract.id().equals(assignment.contractId())) {
                    throw new InvalidAssignmentException("Assignment contract does not match exercise");
                }
                if (assignment.status() != AssignmentStatus.PENDING) {
                    throw new InvalidAssignmentException("Assignment is not pending: " + assignment.id());
                }
                assignedQuantity += assignment.quantity();
            }
            if (assignedQuantity != exercise.quantity()) {
                throw new InvalidAssignmentException("Assigned quantity " + assignedQuantity + " does not equal exercised quantity " + exercise.quantity());
            }
        }
    }

    private void updateLongOptionPosition(Map<String, Position> positions, Contract contract, ExerciseInstruction exercise) {
        Position current = positions.get(key(exercise.accountId(), contract.id()));
        if (current == null || current.quantity() < exercise.quantity()) {
            throw new InsufficientPositionException("Long option position is insufficient for exercise " + exercise.id());
        }
        long remaining = current.quantity() - exercise.quantity();
        putOrRemove(positions, remaining, exercise.accountId(), contract.id());
    }

    private void updateWriterOptionPosition(Map<String, Position> positions, Contract contract, Assignment assignment) {
        Position current = positions.get(key(assignment.writerAccountId(), contract.id()));
        if (current == null || current.quantity() + assignment.quantity() > 0) {
            throw new InsufficientPositionException("Short option position is insufficient for assignment " + assignment.id());
        }
        long remaining = current.quantity() + assignment.quantity();
        putOrRemove(positions, remaining, assignment.writerAccountId(), contract.id());
    }

    private void settleCall(
            Map<String, Position> positions,
            Contract contract,
            ExerciseInstruction exercise,
            List<Assignment> assignments
    ) {
        BigDecimal tradeAmount = contract.strikePrice().multiply(BigDecimal.valueOf(exercise.quantity()));
        requireCash(positions, exercise.accountId(), tradeAmount);
        putPosition(positions, exercise.accountId(), CASH_INSTRUMENT,
                cash(positions, exercise.accountId()).subtract(tradeAmount));
        putPosition(positions, exercise.accountId(), contract.underlying(),
                quantity(positions, exercise.accountId(), contract.underlying()) + exercise.quantity());

        for (Assignment assignment : assignments) {
            BigDecimal assignmentAmount = contract.strikePrice().multiply(BigDecimal.valueOf(assignment.quantity()));
            requireQuantity(positions, assignment.writerAccountId(), contract.underlying(), assignment.quantity());
            putPosition(positions, assignment.writerAccountId(), contract.underlying(),
                    quantity(positions, assignment.writerAccountId(), contract.underlying()) - assignment.quantity());
            putPosition(positions, assignment.writerAccountId(), CASH_INSTRUMENT,
                    cash(positions, assignment.writerAccountId()).add(assignmentAmount));
        }
    }

    private void settlePut(
            Map<String, Position> positions,
            Contract contract,
            ExerciseInstruction exercise,
            List<Assignment> assignments
    ) {
        BigDecimal tradeAmount = contract.strikePrice().multiply(BigDecimal.valueOf(exercise.quantity()));
        requireQuantity(positions, exercise.accountId(), contract.underlying(), exercise.quantity());
        putPosition(positions, exercise.accountId(), contract.underlying(),
                quantity(positions, exercise.accountId(), contract.underlying()) - exercise.quantity());
        putPosition(positions, exercise.accountId(), CASH_INSTRUMENT,
                cash(positions, exercise.accountId()).add(tradeAmount));

        for (Assignment assignment : assignments) {
            BigDecimal assignmentAmount = contract.strikePrice().multiply(BigDecimal.valueOf(assignment.quantity()));
            requireCash(positions, assignment.writerAccountId(), assignmentAmount);
            putPosition(positions, assignment.writerAccountId(), CASH_INSTRUMENT,
                    cash(positions, assignment.writerAccountId()).subtract(assignmentAmount));
            putPosition(positions, assignment.writerAccountId(), contract.underlying(),
                    quantity(positions, assignment.writerAccountId(), contract.underlying()) + assignment.quantity());
        }
    }

    private void requireCash(Map<String, Position> positions, String accountId, BigDecimal required) {
        if (cash(positions, accountId).compareTo(required) < 0) {
            throw new InsufficientPositionException("Cash is insufficient for account " + accountId);
        }
    }

    private void requireQuantity(Map<String, Position> positions, String accountId, String instrumentId, long required) {
        if (quantity(positions, accountId, instrumentId) < required) {
            throw new InsufficientPositionException("Position " + instrumentId + " is insufficient for account " + accountId);
        }
    }

    private BigDecimal cash(Map<String, Position> positions, String accountId) {
        return BigDecimal.valueOf(quantity(positions, accountId, CASH_INSTRUMENT));
    }

    private long quantity(Map<String, Position> positions, String accountId, String instrumentId) {
        Position position = positions.get(key(accountId, instrumentId));
        return position == null ? 0 : position.quantity();
    }

    private void putPosition(Map<String, Position> positions, String accountId, String instrumentId, long quantity) {
        putOrRemove(positions, quantity, accountId, instrumentId);
    }

    private void putPosition(Map<String, Position> positions, String accountId, String instrumentId, BigDecimal amount) {
        putOrRemove(positions, amount.longValueExact(), accountId, instrumentId);
    }

    private void putOrRemove(Map<String, Position> positions, long quantity, String accountId, String instrumentId) {
        if (quantity == 0) {
            positions.remove(key(accountId, instrumentId));
        } else {
            positions.put(key(accountId, instrumentId), new Position(accountId, instrumentId, quantity));
        }
    }

    private static String key(String accountId, String instrumentId) {
        return accountId + "|" + instrumentId;
    }

    public record SettlementResult(ExerciseInstruction exercise, List<Assignment> assignments) {
    }
}
