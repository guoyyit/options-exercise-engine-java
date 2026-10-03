package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditLog;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import com.github.guoyyit.optionsexerciseenginejava.exception.DuplicateExerciseException;
import com.github.guoyyit.optionsexerciseenginejava.exception.InsufficientPositionException;
import com.github.guoyyit.optionsexerciseenginejava.exception.InvalidAssignmentException;
import com.github.guoyyit.optionsexerciseenginejava.market.ContractRepository;
import com.github.guoyyit.optionsexerciseenginejava.market.MarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.market.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.AssignmentRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.AuditLogRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.ExerciseInstructionRepository;
import com.github.guoyyit.optionsexerciseenginejava.service.SettlementService.SettlementResult;
import com.github.guoyyit.optionsexerciseenginejava.store.StateManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ExerciseService {
    private final ContractRepository contractRepository;
    private final PositionRepository positionRepository;
    private final MarketDataProvider marketDataProvider;
    private final ExerciseInstructionRepository exerciseInstructionRepository;
    private final AssignmentRepository assignmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final StateManager stateManager;
    private final SettlementService settlementService;
    private final ExerciseProperties properties;
    private final Map<String, AssignmentAllocator> allocators;
    private final ContractLockRegistry contractLockRegistry;

    public ExerciseService(
            ContractRepository contractRepository,
            PositionRepository positionRepository,
            MarketDataProvider marketDataProvider,
            ExerciseInstructionRepository exerciseInstructionRepository,
            AssignmentRepository assignmentRepository,
            AuditLogRepository auditLogRepository,
            StateManager stateManager,
            SettlementService settlementService,
            ExerciseProperties properties,
            Map<String, AssignmentAllocator> allocators,
            ContractLockRegistry contractLockRegistry
    ) {
        this.contractRepository = contractRepository;
        this.positionRepository = positionRepository;
        this.marketDataProvider = marketDataProvider;
        this.exerciseInstructionRepository = exerciseInstructionRepository;
        this.assignmentRepository = assignmentRepository;
        this.auditLogRepository = auditLogRepository;
        this.stateManager = stateManager;
        this.settlementService = settlementService;
        this.properties = properties;
        this.allocators = allocators;
        this.contractLockRegistry = contractLockRegistry;
    }

    public SettlementResult exerciseAmerican(
            String contractId,
            String accountId,
            long quantity,
            String idempotencyKey,
            String operator
    ) {
        contractLockRegistry.lock(contractId);
        try {
            StateSnapshot snapshot = stateManager.snapshot();
            Contract contract = contractRepository.findById(contractId)
                    .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));
            if (contract.exerciseStyle() != ExerciseStyle.AMERICAN) {
                throw new IllegalArgumentException("Contract is not American: " + contractId);
            }
            if (contract.expiryDate().isBefore(LocalDate.now())) {
                throw new IllegalStateException("American exercise is after expiry: " + contractId);
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive: " + quantity);
            }

            Optional<ExerciseInstruction> existingIdempotent = snapshot.exercises().stream()
                    .filter(instruction -> instruction.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
            if (existingIdempotent.isPresent()) {
                ExerciseInstruction existing = existingIdempotent.get();
                List<Assignment> assignments = snapshot.assignments().stream()
                        .filter(assignment -> assignment.exerciseId().equals(existing.id()))
                        .toList();
                return new SettlementResult(existing, assignments);
            }

            boolean activeExercise = snapshot.exercises().stream()
                    .anyMatch(instruction -> instruction.contractId().equals(contractId)
                            && instruction.accountId().equals(accountId)
                            && (instruction.status() == ExerciseStatus.PENDING || instruction.status() == ExerciseStatus.SETTLED));
            if (activeExercise) {
                throw new DuplicateExerciseException("Position already exercised: " + accountId + "/" + contractId);
            }

            Position longPosition = snapshot.positions().stream()
                    .filter(position -> position.accountId().equals(accountId) && position.instrumentId().equals(contractId))
                    .findFirst()
                    .orElse(null);
            if (longPosition == null || longPosition.quantity() < quantity) {
                throw new InsufficientPositionException("Long position is insufficient: " + accountId + "/" + contractId);
            }

            SettlementPrice settlementPrice = marketDataProvider.getSettlementPrice(contractId, LocalDate.now())
                    .orElseThrow(() -> new IllegalStateException("Missing market price for contract: " + contractId));
            ExerciseInstruction instruction = new ExerciseInstruction(
                    UUID.randomUUID().toString(),
                    contractId,
                    accountId,
                    quantity,
                    ExerciseStyle.AMERICAN,
                    ExerciseStatus.PENDING,
                    "MANUAL",
                    "AMERICAN_EXERCISE",
                    idempotencyKey,
                    Instant.now()
            );
            List<Position> shortPositions = snapshot.positions().stream()
                    .filter(position -> position.instrumentId().equals(contractId) && position.quantity() < 0)
                    .toList();
            List<Assignment> assignments = allocator(properties.getAssignmentStrategy())
                    .allocate(contract, shortPositions, quantity, instruction.id());

            AuditLog auditLog = new AuditLog(
                    UUID.randomUUID().toString(),
                    AuditAction.EXERCISE,
                    operator,
                    instruction.id(),
                    "Manual American exercise",
                    Instant.now()
            );
            stateManager.commit(current -> {
                List<ExerciseInstruction> exercises = new ArrayList<>(current.exercises());
                exercises.add(instruction);
                List<Assignment> allAssignments = new ArrayList<>(current.assignments());
                allAssignments.addAll(assignments);
                List<AuditLog> auditLogs = new ArrayList<>(current.auditLogs());
                auditLogs.add(auditLog);
                return new StateSnapshot(
                        current.contracts(),
                        current.positions(),
                        current.prices(),
                        List.copyOf(exercises),
                        List.copyOf(allAssignments),
                        List.copyOf(auditLogs)
                );
            });
            return new SettlementResult(instruction, assignments);
        } finally {
            contractLockRegistry.unlock(contractId);
        }
    }

    public ExpirationResult processExpiration(LocalDate date, AssignmentStrategy strategy) {
        List<Contract> contracts = contractRepository.findAll().stream()
                .filter(contract -> contract.expiryDate().equals(date))
                .sorted(Comparator.comparing(Contract::id))
                .toList();
        List<ContractResult> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Contract contract : contracts) {
            try {
                results.add(processContract(contract, date, strategy));
            } catch (RuntimeException e) {
                failures.add(contract.id() + ": " + e.getMessage());
            }
        }
        return new ExpirationResult(date, results, failures);
    }

    public ContractResult processContract(Contract contract, LocalDate date, AssignmentStrategy strategy) {
        contractLockRegistry.lock(contract.id());
        try {
            StateSnapshot snapshot = stateManager.snapshot();
            SettlementPrice settlementPrice = marketDataProvider.getSettlementPrice(contract.id(), date)
                    .orElseThrow(() -> new IllegalStateException("Missing settlement price for contract: " + contract.id()));
            List<ExerciseInstruction> existingInstructions = snapshot.exercises().stream()
                    .filter(instruction -> instruction.contractId().equals(contract.id()))
                    .toList();
            List<Position> longPositions = snapshot.positions().stream()
                    .filter(position -> position.instrumentId().equals(contract.id()) && position.quantity() > 0)
                    .sorted(Comparator.comparing(Position::accountId))
                    .toList();

            List<ExerciseInstruction> selected = new ArrayList<>();
            List<ExerciseInstruction> abandoned = new ArrayList<>();
            for (Position longPosition : longPositions) {
                Optional<ExerciseInstruction> existingForAccount = existingInstructions.stream()
                        .filter(instruction -> instruction.accountId().equals(longPosition.accountId()))
                        .findFirst();
                if (existingForAccount.isPresent()) {
                    ExerciseInstruction existing = existingForAccount.get();
                    if (existing.status() == ExerciseStatus.PENDING) {
                        BigDecimal intrinsic = intrinsicValue(contract, settlementPrice.price());
                        if (reachesThreshold(intrinsic)) {
                            selected.add(existing);
                        } else {
                            abandoned.add(abandonedCopy(existing, intrinsic, date));
                        }
                    }
                    continue;
                }

                BigDecimal intrinsic = intrinsicValue(contract, settlementPrice.price());
                String idempotencyKey = "AUTO:" + contract.id() + ":" + longPosition.accountId() + ":" + date;
                if (reachesThreshold(intrinsic)) {
                    ExerciseInstruction instruction = new ExerciseInstruction(
                            UUID.randomUUID().toString(),
                            contract.id(),
                            longPosition.accountId(),
                            longPosition.quantity(),
                            contract.exerciseStyle(),
                            ExerciseStatus.PENDING,
                            "AUTO",
                            "IN_THE_MONEY",
                            idempotencyKey,
                            Instant.now()
                    );
                    selected.add(instruction);
                } else {
                    String reason = intrinsic.signum() <= 0 ? "OUT_OF_THE_MONEY" : "BELOW_THRESHOLD";
                    ExerciseInstruction instruction = new ExerciseInstruction(
                            UUID.randomUUID().toString(),
                            contract.id(),
                            longPosition.accountId(),
                            longPosition.quantity(),
                            contract.exerciseStyle(),
                            ExerciseStatus.ABANDONED,
                            "AUTO",
                            reason,
                            idempotencyKey,
                            Instant.now()
                    );
                    abandoned.add(instruction);
                }
            }

            if (selected.isEmpty()) {
                if (abandoned.isEmpty()) {
                    return new ContractResult(contract, List.of(), List.of());
                }
                List<AuditLog> auditLogs = abandoned.stream()
                        .map(instruction -> new AuditLog(
                                UUID.randomUUID().toString(),
                                AuditAction.AUTO_ABANDON,
                                "SYSTEM",
                                instruction.id(),
                                "Abandoned position: " + instruction.reason(),
                                Instant.now()
                        ))
                        .toList();
                stateManager.commit(current -> withExercisesAndAudits(current, abandoned, auditLogs));
                return new ContractResult(contract, List.of(), abandoned);
            }

            long totalExercised = selected.stream().mapToLong(ExerciseInstruction::quantity).sum();
            List<Position> shortPositions = snapshot.positions().stream()
                    .filter(position -> position.instrumentId().equals(contract.id()) && position.quantity() < 0)
                    .sorted(Comparator.comparing(Position::accountId))
                    .toList();
            long totalShort = shortPositions.stream().mapToLong(position -> -position.quantity()).sum();
            if (totalShort < totalExercised) {
                throw new InvalidAssignmentException("Insufficient short positions for contract: " + contract.id());
            }

            Map<String, List<Assignment>> assignmentsByExercise = new HashMap<>();
            Map<String, Long> assignedByWriter = new HashMap<>();
            long alreadyAssigned = 0;
            for (ExerciseInstruction exercise : selected) {
                List<Assignment> existingAssignments = snapshot.assignments().stream()
                        .filter(assignment -> assignment.exerciseId().equals(exercise.id()))
                        .filter(assignment -> assignment.status() == AssignmentStatus.PENDING)
                        .toList();
                if (existingAssignments.isEmpty()) {
                    assignmentsByExercise.put(exercise.id(), new ArrayList<>());
                    continue;
                }
                long existingQuantity = existingAssignments.stream().mapToLong(Assignment::quantity).sum();
                if (existingQuantity != exercise.quantity()) {
                    throw new InvalidAssignmentException("Existing assignment quantity does not match exercise: " + exercise.id());
                }
                assignmentsByExercise.put(exercise.id(), existingAssignments);
                alreadyAssigned += existingQuantity;
                existingAssignments.forEach(assignment -> assignedByWriter.merge(assignment.writerAccountId(), assignment.quantity(), Long::sum));
            }

            long toAllocate = totalExercised - alreadyAssigned;
            if (toAllocate < 0) {
                throw new InvalidAssignmentException("Already assigned more than exercised quantity: " + contract.id());
            }
            if (toAllocate > 0) {
                List<Position> availableShorts = new ArrayList<>();
                for (Position shortPosition : shortPositions) {
                    long assigned = assignedByWriter.getOrDefault(shortPosition.accountId(), 0L);
                    long available = -shortPosition.quantity() - assigned;
                    if (available < 0) {
                        throw new InvalidAssignmentException("Writer has already been over-assigned: " + shortPosition.accountId());
                    }
                    if (available > 0) {
                        availableShorts.add(new Position(shortPosition.accountId(), contract.id(), -available));
                    }
                }
                List<Assignment> allocation = allocator(strategy).allocate(contract, availableShorts, toAllocate, "AUTO:" + contract.id());
                Map<String, Long> writerTotals = allocation.stream()
                        .collect(Collectors.groupingBy(Assignment::writerAccountId, Collectors.summingLong(Assignment::quantity)));
                List<ExerciseInstruction> exercisesToAllocate = selected.stream()
                        .filter(exercise -> assignmentsByExercise.get(exercise.id()).isEmpty())
                        .toList();
                Map<String, List<Assignment>> newAssignments = splitAssignments(contract, exercisesToAllocate, writerTotals, Instant.now());
                newAssignments.forEach((exerciseId, assignments) -> assignmentsByExercise.get(exerciseId).addAll(assignments));
            }
            List<AuditLog> exerciseAudits = selected.stream()
                    .map(instruction -> new AuditLog(
                            UUID.randomUUID().toString(),
                            AuditAction.AUTO_EXERCISE,
                            "SYSTEM",
                            instruction.id(),
                            "Auto exercise at settlement price " + settlementPrice.price(),
                            Instant.now()
                    ))
                    .toList();

            List<SettlementResult> results = settlementService.settleBatch(
                    contract,
                    selected,
                    assignmentsByExercise,
                    settlementPrice,
                    exerciseAudits,
                    abandoned
            );
            return new ContractResult(contract, results, abandoned);
        } finally {
            contractLockRegistry.unlock(contract.id());
        }
    }

    private AssignmentAllocator allocator(AssignmentStrategy strategy) {
        AssignmentAllocator allocator = allocators.get(switch (strategy) {
            case PRO_RATA -> "proRataAssignmentAllocator";
            case RANDOM -> "randomAssignmentAllocator";
        });
        if (allocator == null) {
            throw new IllegalStateException("Assignment allocator not found: " + strategy);
        }
        return allocator;
    }

    private BigDecimal intrinsicValue(Contract contract, BigDecimal settlementPrice) {
        BigDecimal intrinsic = contract.optionType() == OptionType.CALL
                ? settlementPrice.subtract(contract.strikePrice())
                : contract.strikePrice().subtract(settlementPrice);
        return intrinsic.max(BigDecimal.ZERO);
    }

    private boolean reachesThreshold(BigDecimal intrinsic) {
        return intrinsic.signum() > 0 && intrinsic.compareTo(properties.getIntrinsicThreshold()) >= 0;
    }

    private ExerciseInstruction abandonedCopy(ExerciseInstruction existing, BigDecimal intrinsic, LocalDate date) {
        String reason = intrinsic.signum() <= 0 ? "OUT_OF_THE_MONEY" : "BELOW_THRESHOLD";
        return new ExerciseInstruction(
                existing.id(),
                existing.contractId(),
                existing.accountId(),
                existing.quantity(),
                existing.exerciseStyle(),
                ExerciseStatus.ABANDONED,
                existing.source(),
                reason,
                existing.idempotencyKey(),
                existing.createdAt()
        );
    }

    private StateSnapshot withExercisesAndAudits(StateSnapshot snapshot, List<ExerciseInstruction> newExercises, List<AuditLog> newAudits) {
        List<ExerciseInstruction> exercises = new ArrayList<>(snapshot.exercises());
        exercises.addAll(newExercises);
        List<AuditLog> auditLogs = new ArrayList<>(snapshot.auditLogs());
        auditLogs.addAll(newAudits);
        return new StateSnapshot(
                snapshot.contracts(),
                snapshot.positions(),
                snapshot.prices(),
                List.copyOf(exercises),
                snapshot.assignments(),
                List.copyOf(auditLogs)
        );
    }

    private Map<String, List<Assignment>> splitAssignments(
            Contract contract,
            List<ExerciseInstruction> exercises,
            Map<String, Long> writerTotals,
            Instant now
    ) {
        Map<String, List<Assignment>> result = new HashMap<>();
        for (ExerciseInstruction exercise : exercises) {
            result.put(exercise.id(), new ArrayList<>());
        }

        long totalExercised = exercises.stream().mapToLong(ExerciseInstruction::quantity).sum();
        List<String> writers = writerTotals.keySet().stream().sorted().toList();
        for (String writer : writers) {
            long writerTotal = writerTotals.get(writer);
            long allocated = 0;
            record Quota(ExerciseInstruction exercise, long floor, long remainder) {
            }
            List<Quota> quotas = new ArrayList<>();
            for (ExerciseInstruction exercise : exercises) {
                long product = Math.multiplyExact(writerTotal, exercise.quantity());
                long floor = Math.floorDiv(product, totalExercised);
                long remainder = product - floor * totalExercised;
                quotas.add(new Quota(exercise, floor, remainder));
                allocated += floor;
            }

            long remaining = writerTotal - allocated;
            quotas.sort(Comparator.comparingLong(Quota::remainder).reversed().thenComparing(quota -> quota.exercise().id()));
            for (int i = 0; i < remaining; i++) {
                Quota quota = quotas.get(i);
                quotas.set(i, new Quota(quota.exercise(), quota.floor() + 1, quota.remainder()));
            }

            for (Quota quota : quotas) {
                if (quota.floor() > 0) {
                    result.get(quota.exercise().id()).add(new Assignment(
                            UUID.randomUUID().toString(),
                            quota.exercise().id(),
                            contract.id(),
                            writer,
                            quota.floor(),
                            AssignmentStatus.PENDING,
                            now
                    ));
                }
            }
        }
        return result;
    }

    public record ContractResult(
            Contract contract,
            List<SettlementResult> settled,
            List<ExerciseInstruction> abandoned
    ) {
    }

    public record ExpirationResult(LocalDate date, List<ContractResult> results, List<String> failures) {
    }
}
