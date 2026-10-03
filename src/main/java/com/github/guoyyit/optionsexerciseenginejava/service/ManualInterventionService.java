package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditLog;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.Operator;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import com.github.guoyyit.optionsexerciseenginejava.exception.ExerciseCutoffException;
import com.github.guoyyit.optionsexerciseenginejava.exception.InsufficientPositionException;
import com.github.guoyyit.optionsexerciseenginejava.exception.InvalidAssignmentException;
import com.github.guoyyit.optionsexerciseenginejava.exception.UnauthorizedInterventionException;
import com.github.guoyyit.optionsexerciseenginejava.market.ContractRepository;
import com.github.guoyyit.optionsexerciseenginejava.store.StateManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ManualInterventionService {
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String REVOKE_ROLE = "REVOKE_EXERCISE";
    private static final String ADJUST_ROLE = "ADJUST_ASSIGNMENT";

    private final StateManager stateManager;
    private final ContractRepository contractRepository;
    private final ContractLockRegistry contractLockRegistry;
    private final ExerciseProperties properties;

    public ManualInterventionService(
            StateManager stateManager,
            ContractRepository contractRepository,
            ContractLockRegistry contractLockRegistry,
            ExerciseProperties properties
    ) {
        this.stateManager = stateManager;
        this.contractRepository = contractRepository;
        this.contractLockRegistry = contractLockRegistry;
        this.properties = properties;
    }

    public ExerciseInstruction revokeExercise(String instructionId, String reason, Operator operator) {
        requireRole(operator, REVOKE_ROLE);
        StateSnapshot snapshot = stateManager.snapshot();
        ExerciseInstruction currentInstruction = snapshot.exercises().stream()
                .filter(item -> item.id().equals(instructionId))
                .findFirst()
                .orElseThrow(() -> new InvalidAssignmentException("Exercise not found: " + instructionId));
        Contract contract = contractRepository.findById(currentInstruction.contractId())
                .orElseThrow(() -> new InvalidAssignmentException("Contract not found: " + currentInstruction.contractId()));
        contractLockRegistry.lock(contract.id());
        try {
            snapshot = stateManager.snapshot();
            ExerciseInstruction instruction = snapshot.exercises().stream()
                    .filter(item -> item.id().equals(instructionId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidAssignmentException("Exercise not found: " + instructionId));
            requireBeforeCutoff(contract);
            if (instruction.status() == ExerciseStatus.CANCELLED) {
                return instruction;
            }
            if (instruction.status() != ExerciseStatus.PENDING) {
                throw new InvalidAssignmentException("Only pending exercise can be revoked: " + instructionId);
            }

            List<ExerciseInstruction> exercises = new ArrayList<>(snapshot.exercises());
            for (int i = 0; i < exercises.size(); i++) {
                if (exercises.get(i).id().equals(instructionId)) {
                    exercises.set(i, new ExerciseInstruction(
                            instruction.id(),
                            instruction.contractId(),
                            instruction.accountId(),
                            instruction.quantity(),
                            instruction.exerciseStyle(),
                            ExerciseStatus.CANCELLED,
                            instruction.source(),
                            reason == null || reason.isBlank() ? "MANUAL_REVOKE" : reason,
                            instruction.idempotencyKey(),
                            instruction.createdAt()
                    ));
                    break;
                }
            }

            List<Assignment> assignments = new ArrayList<>(snapshot.assignments());
            for (int i = 0; i < assignments.size(); i++) {
                Assignment assignment = assignments.get(i);
                if (assignment.exerciseId().equals(instructionId)) {
                    if (assignment.status() == AssignmentStatus.SETTLED) {
                        throw new InvalidAssignmentException("Settled assignment cannot be revoked: " + assignment.id());
                    }
                    if (assignment.status() == AssignmentStatus.PENDING) {
                        assignments.set(i, new Assignment(
                                assignment.id(),
                                assignment.exerciseId(),
                                assignment.contractId(),
                                assignment.writerAccountId(),
                                assignment.quantity(),
                                AssignmentStatus.CANCELLED,
                                assignment.createdAt()
                        ));
                    }
                }
            }

            List<AuditLog> auditLogs = new ArrayList<>(snapshot.auditLogs());
            auditLogs.add(new AuditLog(
                    UUID.randomUUID().toString(),
                    AuditAction.REVOKE_EXERCISE,
                    operator.id(),
                    instructionId,
                    "Revoked exercise: " + (reason == null ? "" : reason),
                    Instant.now()
            ));
            commit(snapshot, exercises, assignments, auditLogs);
            return exercises.stream().filter(item -> item.id().equals(instructionId)).findFirst().orElseThrow();
        } finally {
            contractLockRegistry.unlock(contract.id());
        }
    }

    public Assignment adjustAssignment(String assignmentId, String newWriterAccountId, String reason, Operator operator) {
        requireRole(operator, ADJUST_ROLE);
        StateSnapshot snapshot = stateManager.snapshot();
        Assignment currentAssignment = snapshot.assignments().stream()
                .filter(item -> item.id().equals(assignmentId))
                .findFirst()
                .orElseThrow(() -> new InvalidAssignmentException("Assignment not found: " + assignmentId));
        Contract contract = contractRepository.findById(currentAssignment.contractId())
                .orElseThrow(() -> new InvalidAssignmentException("Contract not found: " + currentAssignment.contractId()));
        if (newWriterAccountId == null || newWriterAccountId.isBlank()) {
            throw new InvalidAssignmentException("New writer account is required");
        }
        if (newWriterAccountId.equals(currentAssignment.writerAccountId())) {
            throw new InvalidAssignmentException("Assignment is already assigned to writer: " + newWriterAccountId);
        }

        contractLockRegistry.lock(contract.id());
        try {
            snapshot = stateManager.snapshot();
            Assignment assignment = snapshot.assignments().stream()
                    .filter(item -> item.id().equals(assignmentId))
                    .findFirst()
                    .orElseThrow(() -> new InvalidAssignmentException("Assignment not found: " + assignmentId));
            requireBeforeCutoff(contract);
            if (assignment.status() != AssignmentStatus.PENDING) {
                throw new InvalidAssignmentException("Only pending assignment can be adjusted: " + assignmentId);
            }
            Position newWriterPosition = snapshot.positions().stream()
                    .filter(position -> position.accountId().equals(newWriterAccountId) && position.instrumentId().equals(contract.id()))
                    .findFirst()
                    .orElse(null);
            if (newWriterPosition == null || -newWriterPosition.quantity() < assignment.quantity()) {
                throw new InsufficientPositionException("New writer has insufficient short position: " + newWriterAccountId);
            }

            List<Assignment> assignments = new ArrayList<>(snapshot.assignments());
            for (int i = 0; i < assignments.size(); i++) {
                if (assignments.get(i).id().equals(assignmentId)) {
                    assignments.set(i, new Assignment(
                            assignment.id(),
                            assignment.exerciseId(),
                            assignment.contractId(),
                            newWriterAccountId,
                            assignment.quantity(),
                            assignment.status(),
                            assignment.createdAt()
                    ));
                    break;
                }
            }

            List<AuditLog> auditLogs = new ArrayList<>(snapshot.auditLogs());
            auditLogs.add(new AuditLog(
                    UUID.randomUUID().toString(),
                    AuditAction.ADJUST_ASSIGNMENT,
                    operator.id(),
                    assignmentId,
                    "Adjusted assignment writer from " + assignment.writerAccountId() + " to " + newWriterAccountId + ": " + reason,
                    Instant.now()
            ));
            commit(snapshot, snapshot.exercises(), assignments, auditLogs);
            return assignments.stream().filter(item -> item.id().equals(assignmentId)).findFirst().orElseThrow();
        } finally {
            contractLockRegistry.unlock(contract.id());
        }
    }

    private void requireRole(Operator operator, String role) {
        if (operator == null || operator.id() == null || operator.roles() == null) {
            throw new UnauthorizedInterventionException("Operator authentication is required");
        }
        if (!operator.roles().contains(ADMIN_ROLE) && !operator.roles().contains(role)) {
            throw new UnauthorizedInterventionException("Operator lacks role: " + role);
        }
    }

    private void requireBeforeCutoff(Contract contract) {
        ZonedDateTime cutoff = contract.expiryDate()
                .atTime(LocalTime.of(properties.getCutoffHour(), 0))
                .atZone(properties.getZoneId());
        if (!Instant.now().isBefore(cutoff.toInstant())) {
            throw new ExerciseCutoffException("Intervention cutoff has passed for contract: " + contract.id());
        }
    }

    private void commit(StateSnapshot snapshot, List<ExerciseInstruction> exercises, List<Assignment> assignments, List<AuditLog> auditLogs) {
        stateManager.commit(current -> new StateSnapshot(
                current.contracts(),
                current.positions(),
                current.prices(),
                List.copyOf(exercises),
                List.copyOf(assignments),
                List.copyOf(auditLogs)
        ));
    }
}
