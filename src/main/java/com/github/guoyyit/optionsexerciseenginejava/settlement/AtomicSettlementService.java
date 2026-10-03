package com.github.guoyyit.optionsexerciseenginejava.settlement;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementEvent;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementEventType;
import com.github.guoyyit.optionsexerciseenginejava.repository.AccountRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.state.EngineStateStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class AtomicSettlementService implements SettlementService {

    private static final int LOCK_STRIPES = 256;

    private final PositionRepository positionRepository;
    private final AccountRepository accountRepository;
    private final EngineStateStore stateStore;
    private final ContractLockManager contractLockManager;
    private final Lock[] locks;
    private final Set<String> settledRefs = ConcurrentHashMap.newKeySet();
    private final Set<String> adjustedRefs = ConcurrentHashMap.newKeySet();
    private volatile boolean recovered;

    public AtomicSettlementService(PositionRepository positionRepository,
                                   AccountRepository accountRepository,
                                   EngineStateStore stateStore,
                                   ContractLockManager contractLockManager) {
        this.positionRepository = positionRepository;
        this.accountRepository = accountRepository;
        this.stateStore = stateStore;
        this.contractLockManager = contractLockManager;
        this.locks = new Lock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private Lock lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    @Override
    public void settleLongExercise(String batchId, Position longPosition, long settlePrice,
                                   List<Assignment> assignments) {
        String ref = "exercise:" + longPosition.getId();
        String txId = (batchId == null ? "manual" : batchId) + ":" + longPosition.getId();
        settleExercise(txId, ref, longPosition, settlePrice, assignments, null);
    }

    @Override
    public void settleManualExercise(String positionId, Position longPosition, long settlePrice,
                                     List<Assignment> assignments) {
        String ref = "exercise:" + positionId;
        String txId = "manual:" + positionId;
        settleExercise(txId, ref, longPosition, settlePrice, assignments, null);
    }

    private void settleExercise(String txId, String ref, Position longPosition, long settlePrice,
                                List<Assignment> assignments, String operator) {
        ensureRecovered();
        Lock contractLock = contractLockManager.contractLock(longPosition.getContractCode());
        Lock refLock = lockFor(ref);
        contractLock.lock();
        refLock.lock();
        try {
            if (settledRefs.contains(ref)) {
                return;
            }
            long exercised = longPosition.getQuantity();
            long assignedTotal = assignments.stream().mapToLong(Assignment::quantity).sum();
            if (assignedTotal != exercised) {
                throw new IllegalArgumentException(
                        "assignment total " + assignedTotal + " != exercised " + exercised + " for " + ref);
            }
            long longCashDelta = longPosition.getOptionType() == OptionType.CALL
                    ? -settlePrice * exercised : settlePrice * exercised;
            long longUnderlyingDelta =
                    longPosition.getOptionType() == OptionType.CALL ? exercised : -exercised;

            List<SettlementEvent> events = new ArrayList<>();
            long seq = 0;
            events.add(new SettlementEvent(seq++, ref, SettlementEventType.EXERCISE,
                    longPosition.getAccountId(), longPosition.getId(),
                    longPosition.getContractCode(), -exercised, longCashDelta,
                    longUnderlyingDelta, operator, "long exercise at " + settlePrice));
            long cashConsumed = 0L;
            long underlyingConsumed = 0L;
            for (int i = 0; i < assignments.size(); i++) {
                Assignment assignment = assignments.get(i);
                long shortCashDelta;
                long shortUnderlyingDelta;
                if (i == assignments.size() - 1) {
                    shortCashDelta = -longCashDelta - cashConsumed;
                    shortUnderlyingDelta = -longUnderlyingDelta - underlyingConsumed;
                } else {
                    shortCashDelta = -longCashDelta * assignment.quantity() / exercised;
                    shortUnderlyingDelta = -longUnderlyingDelta * assignment.quantity() / exercised;
                    cashConsumed += shortCashDelta;
                    underlyingConsumed += shortUnderlyingDelta;
                }
                events.add(new SettlementEvent(seq++, ref, SettlementEventType.ASSIGNMENT,
                        assignment.accountId(), assignment.shortPositionId(),
                        assignment.contractCode(), assignment.quantity(),
                        shortCashDelta, shortUnderlyingDelta, operator,
                        "assigned " + assignment.quantity()));
            }
            validateThenCommit(txId, ref, events);
        } finally {
            refLock.unlock();
            contractLock.unlock();
        }
    }

    @Override
    public void applyAssignmentAdjustment(String referenceId, Assignment from, Assignment to, String operator) {
        ensureRecovered();
        if (from.quantity() != to.quantity()) {
            throw new IllegalArgumentException("adjustment must preserve total assigned quantity");
        }
        if (from.shortPositionId().equals(to.shortPositionId())) {
            throw new IllegalArgumentException("adjustment source and target must be different positions");
        }
        String ref = "adjust:" + referenceId;
        Lock lock = lockFor(ref);
        lock.lock();
        try {
            if (adjustedRefs.contains(ref)) {
                return;
            }
            List<SettlementEvent> events = List.of(
                    new SettlementEvent(0, ref, SettlementEventType.ASSIGNMENT_ADJUSTMENT,
                            from.accountId(), from.shortPositionId(), from.contractCode(),
                            from.quantity(), 0L, 0L, operator, "release assigned short"),
                    new SettlementEvent(1, ref, SettlementEventType.ASSIGNMENT_ADJUSTMENT,
                            to.accountId(), to.shortPositionId(), to.contractCode(),
                            -to.quantity(), 0L, 0L, operator, "reassign short"));
            validateThenCommit("adjust:" + referenceId, ref, events);
        } finally {
            lock.unlock();
        }
    }

    private void validateThenCommit(String txId, String ref, List<SettlementEvent> events) {
        validate(events);
        stateStore.beginTransaction(txId, ref);
        for (SettlementEvent event : events) {
            stateStore.appendEvent(txId, event);
        }
        stateStore.commitTransaction(txId);
        try {
            applyEvents(events);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "committed events could not be applied in memory for " + ref
                            + "; state is durable and will be restored on restart", e);
        }
        if (ref.startsWith("adjust:")) {
            adjustedRefs.add(ref);
        } else {
            settledRefs.add(ref);
        }
    }

    private void validate(List<SettlementEvent> events) {
        Map<String, Long> positionAfter = new java.util.HashMap<>();
        Map<String, Long> cashAfter = new java.util.HashMap<>();
        for (SettlementEvent event : events) {
            if (event.positionId() != null) {
                Position position = positionRepository.findById(event.positionId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "position missing during validation: " + event.positionId()));
                long current = position.getQuantity() + positionAfter.getOrDefault(event.positionId(), 0L);
                long next = current + event.positionDelta();
                if (position.getQuantity() < 0 && next > 0) {
                    throw new IllegalStateException(
                            "short position would flip long: " + event.positionId());
                }
                if (position.getQuantity() > 0 && next < 0) {
                    throw new IllegalStateException(
                            "long position would flip short: " + event.positionId());
                }
                positionAfter.merge(event.positionId(), event.positionDelta(), Long::sum);
            }
            if (event.accountId() != null) {
                long current = accountRepository.findById(event.accountId())
                        .map(a -> a.getCash() + cashAfter.getOrDefault(event.accountId(), 0L))
                        .orElse(0L);
                if (current + event.cashDelta() < 0) {
                    throw new IllegalStateException("cash would go negative: " + event.accountId());
                }
                cashAfter.merge(event.accountId(), event.cashDelta(), Long::sum);
            }
        }
    }

    private void applyEvents(List<SettlementEvent> events) {
        for (SettlementEvent event : events) {
            if (event.positionId() != null && event.positionDelta() != 0) {
                positionRepository.adjustQuantity(event.positionId(), event.positionDelta());
            }
            if (event.accountId() != null) {
                if (event.cashDelta() != 0) {
                    accountRepository.adjustCash(event.accountId(), event.cashDelta());
                }
                if (event.underlyingDelta() != 0) {
                    accountRepository.adjustUnderlying(event.accountId(), event.underlyingDelta());
                }
            }
        }
    }

    @Override
    public boolean isPositionSettled(String positionId) {
        return settledRefs.contains("exercise:" + positionId);
    }

    @Override
    public synchronized void recover() {
        if (recovered) {
            return;
        }
        for (EngineStateStore.RecoverableTransaction tx : stateStore.pendingTransactions()) {
            stateStore.truncateTransaction(tx.transactionId());
        }
        for (EngineStateStore.RecoverableTransaction tx : stateStore.committedTransactions()) {
            if (tx.settlementRef().startsWith("exercise:")) {
                applyEvents(tx.events());
                settledRefs.add(tx.settlementRef());
            } else if (tx.settlementRef().startsWith("adjust:")) {
                applyEvents(tx.events());
                adjustedRefs.add(tx.settlementRef());
            }
        }
        recovered = true;
    }

    private void ensureRecovered() {
        if (!recovered) {
            recover();
        }
    }
}
