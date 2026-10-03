package com.github.guoyyit.optionsexerciseenginejava.engine;

import com.github.guoyyit.optionsexerciseenginejava.assignment.AssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseDecision;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.intervention.AuditLog;
import com.github.guoyyit.optionsexerciseenginejava.intervention.DefaultInterventionService;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.settlement.ContractLockManager;
import com.github.guoyyit.optionsexerciseenginejava.settlement.SettlementService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DefaultExpirationEngine implements ExpirationEngine {

    private final ExpirationScanner scanner;
    private final BatchExecutor batchExecutor;
    private final SettlementService settlementService;
    private final PositionRepository positionRepository;
    private final AssignmentStrategy proportionalStrategy;
    private final AssignmentStrategy randomStrategy;
    private final EngineProperties properties;
    private final AuditLog auditLog;
    private final DefaultInterventionService interventionService;
    private final ContractLockManager contractLockManager;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    public DefaultExpirationEngine(ExpirationScanner scanner,
                                   BatchExecutor batchExecutor,
                                   SettlementService settlementService,
                                   PositionRepository positionRepository,
                                   @Qualifier("proportionalAssignmentStrategy") AssignmentStrategy proportionalStrategy,
                                   @Qualifier("randomAssignmentStrategy") AssignmentStrategy randomStrategy,
                                   EngineProperties properties,
                                   AuditLog auditLog,
                                   DefaultInterventionService interventionService,
                                   ContractLockManager contractLockManager) {
        this.scanner = scanner;
        this.batchExecutor = batchExecutor;
        this.settlementService = settlementService;
        this.positionRepository = positionRepository;
        this.proportionalStrategy = proportionalStrategy;
        this.randomStrategy = randomStrategy;
        this.properties = properties;
        this.auditLog = auditLog;
        this.interventionService = interventionService;
        this.contractLockManager = contractLockManager;
    }

    @Override
    public EngineRunResult processExpiration(long expiryDate) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("an expiration run is already in progress");
        }
        cancelled.set(false);
        try {
            settlementService.recover();
            interventionService.openWindow();
            List<ExerciseDecision> decisions = scanner.scan(expiryDate);
            List<ExerciseDecision> pending = decisions.stream()
                    .filter((ExerciseDecision d) -> !settlementService.isPositionSettled(d.position().getId()))
                    .sorted(Comparator.comparing((ExerciseDecision d) -> d.position().getContractCode())
                            .thenComparing((ExerciseDecision d) -> d.position().getId()))
                    .toList();

            AtomicInteger exercised = new AtomicInteger();
            AtomicInteger abandoned = new AtomicInteger();
            AtomicInteger assigned = new AtomicInteger();

            BatchExecutor.BatchResult result =
                    batchExecutor.runBatches("exp-" + expiryDate, pending, batch -> processBatch(
                            batch, expiryDate, exercised, abandoned, assigned));
            return new EngineRunResult(expiryDate, decisions.size(), exercised.get(), abandoned.get(),
                    assigned.get(), result.cancelled() || cancelled.get(), result.timedOut());
        } finally {
            running.set(false);
        }
    }

    private void processBatch(List<ExerciseDecision> batch, long expiryDate,
                              AtomicInteger exercised, AtomicInteger abandoned, AtomicInteger assigned) {
        String firstContract = batch.get(0).position().getContractCode();
        String lastContract = batch.get(batch.size() - 1).position().getContractCode();
        if (firstContract.equals(lastContract)) {
            processContract(firstContract, batch, expiryDate, exercised, abandoned, assigned);
            return;
        }
        List<List<ExerciseDecision>> groups = new ArrayList<>();
        String current = null;
        List<ExerciseDecision> group = null;
        for (ExerciseDecision decision : batch) {
            String contract = decision.position().getContractCode();
            if (!contract.equals(current)) {
                group = new ArrayList<>();
                groups.add(group);
                current = contract;
            }
            group.add(decision);
        }
        for (List<ExerciseDecision> nextGroup : groups) {
            if (cancelled.get()) {
                return;
            }
            processContract(nextGroup.get(0).position().getContractCode(), nextGroup,
                    expiryDate, exercised, abandoned, assigned);
        }
    }

    private void processContract(String contractCode, List<ExerciseDecision> decisions, long expiryDate,
                                 AtomicInteger exercised, AtomicInteger abandoned, AtomicInteger assigned) {
        java.util.concurrent.locks.ReentrantLock lock =
                contractLockManager.contractLock(contractCode);
        lock.lock();
        try {
            List<Position> shorts = positionRepository.findByContractCode(contractCode).stream()
                    .filter(Position::isShort)
                    .sorted(Comparator.comparing(Position::getId))
                    .toList();
            long nonce = (expiryDate << 20) ^ (long) contractCode.hashCode();
            List<ExerciseDecision> toExercise = new ArrayList<>();
            for (ExerciseDecision decision : decisions) {
                if (cancelled.get()) {
                    return;
                }
                Position position = decision.position();
                if (settlementService.isPositionSettled(position.getId())) {
                    continue;
                }
                if (decision.action() == ExerciseAction.ABANDON) {
                    abandoned.incrementAndGet();
                    auditLog.record("SYSTEM", "AUTO_ABANDON", position.getId(),
                            "contract=" + contractCode + " " + decision.reason(), true, decision.reason());
                } else {
                    toExercise.add(decision);
                }
            }
            if (toExercise.isEmpty()) {
                return;
            }
            List<ExerciseDecision> stillPending = toExercise.stream()
                    .filter(d -> !settlementService.isPositionSettled(d.position().getId()))
                    .toList();
            long exercisedTotal = stillPending.stream()
                    .mapToLong(d -> d.position().getQuantity()).sum();
            if (exercisedTotal == 0) {
                return;
            }
            AssignmentStrategy strategy = selectStrategy();
            List<Assignment> allocation = strategy.allocate(contractCode, exercisedTotal, shorts, nonce);
            long allocatedTotal = allocation.stream().mapToLong(Assignment::quantity).sum();
            if (allocatedTotal != exercisedTotal) {
                throw new IllegalStateException(
                        "allocated " + allocatedTotal + " != exercised " + exercisedTotal);
            }
            long offset = 0L;
            for (ExerciseDecision decision : stillPending) {
                if (settlementService.isPositionSettled(decision.position().getId())) {
                    offset += decision.position().getQuantity();
                    continue;
                }
                long need = decision.position().getQuantity();
                List<Assignment> slice = takeSlice(allocation, offset, need);
                offset += need;
                settlementService.settleLongExercise(
                        "exp-" + expiryDate + ":" + contractCode + ":" + decision.position().getId(),
                        decision.position(), decision.settlementPrice(), slice);
                exercised.incrementAndGet();
                assigned.addAndGet(slice.size());
            }
        } finally {
            lock.unlock();
        }
    }

    private List<Assignment> takeSlice(List<Assignment> allocation, long offset, long need) {
        List<Assignment> slice = new ArrayList<>();
        long windowEnd = offset + need;
        long positionStart = 0L;
        for (Assignment assignment : allocation) {
            long positionEnd = positionStart + assignment.quantity();
            long overlapStart = Math.max(offset, positionStart);
            long overlapEnd = Math.min(windowEnd, positionEnd);
            if (overlapEnd > overlapStart) {
                slice.add(new Assignment(assignment.shortPositionId(), assignment.accountId(),
                        assignment.contractCode(), overlapEnd - overlapStart));
            }
            positionStart = positionEnd;
        }
        long total = slice.stream().mapToLong(Assignment::quantity).sum();
        if (total != need) {
            throw new IllegalStateException("allocation slice " + total + " != needed " + need);
        }
        return slice;
    }

    private AssignmentStrategy selectStrategy() {
        return "random".equalsIgnoreCase(properties.getAssignmentStrategy())
                ? randomStrategy : proportionalStrategy;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        if (batchExecutor instanceof ConfigurableBatchExecutor configurable) {
            configurable.cancel();
        }
    }
}
