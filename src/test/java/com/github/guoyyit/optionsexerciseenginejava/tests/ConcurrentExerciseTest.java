package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.optionsexerciseenginejava.assignment.ProportionalAssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.assignment.RandomAssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.Account;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import com.github.guoyyit.optionsexerciseenginejava.engine.ConfigurableBatchExecutor;
import com.github.guoyyit.optionsexerciseenginejava.engine.DefaultExpirationEngine;
import com.github.guoyyit.optionsexerciseenginejava.engine.DefaultExpirationScanner;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import com.github.guoyyit.optionsexerciseenginejava.exercise.DefaultExerciseDecisionService;
import com.github.guoyyit.optionsexerciseenginejava.exercise.DefaultExerciseInstructionService;
import com.github.guoyyit.optionsexerciseenginejava.intervention.DefaultInterventionService;
import com.github.guoyyit.optionsexerciseenginejava.intervention.FileBackedAuditLog;
import com.github.guoyyit.optionsexerciseenginejava.intervention.SimplePermissionService;
import com.github.guoyyit.optionsexerciseenginejava.market.InMemoryMarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.repository.AccountRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryAccountRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryPositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.settlement.AtomicSettlementService;
import com.github.guoyyit.optionsexerciseenginejava.settlement.ContractLockManager;
import com.github.guoyyit.optionsexerciseenginejava.state.FileEngineStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentExerciseTest {

    static final long EXPIRY = 20260101L;

    @TempDir
    Path tempDir;

    static class Harness {
        PositionRepository positions = new InMemoryPositionRepository();
        AccountRepository accounts = new InMemoryAccountRepository();
        InMemoryMarketDataProvider marketData = new InMemoryMarketDataProvider();
        EngineProperties properties;
        AtomicSettlementService settlement;
        ContractLockManager lockManager;
        DefaultExerciseInstructionService instructions;
        DefaultExpirationEngine engine;


        Harness(Path dir) {
            properties = new EngineProperties();
            properties.setStateDir(dir.toString());
            properties.setBatchSize(1);
            properties.setConcurrency(4);
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            FileEngineStateStore stateStore = new FileEngineStateStore(properties, objectMapper);
            lockManager = new ContractLockManager();
            settlement = new AtomicSettlementService(positions, accounts, stateStore, lockManager);
            instructions = new DefaultExerciseInstructionService(positions);
            DefaultExerciseDecisionService decisions = new DefaultExerciseDecisionService();
            DefaultExpirationScanner scanner = new DefaultExpirationScanner(
                    positions, marketData, decisions, instructions, properties);
            ConfigurableBatchExecutor batchExecutor = new ConfigurableBatchExecutor(properties);
            FileBackedAuditLog auditLog = new FileBackedAuditLog(properties, objectMapper);
            DefaultInterventionService intervention = new DefaultInterventionService(
                    new SimplePermissionService(), auditLog, settlement, instructions, properties);
            engine = new DefaultExpirationEngine(scanner, batchExecutor, settlement, positions,
                    new ProportionalAssignmentStrategy(),
                    new RandomAssignmentStrategy(properties), properties, auditLog, intervention,
                    lockManager);
        }
    }

    @Test
    void concurrentAutoAndManualExerciseNeverDoubleSettles() throws Exception {
        Harness h = new Harness(tempDir);
        int n = 20;
        for (int i = 0; i < n; i++) {
            String code = "CALL-" + i;
            h.positions.save(new Position("long-" + i, "buyer-" + i, code, OptionType.CALL,
                    ExerciseStyle.AMERICAN, 90L, 10L, EXPIRY));
            h.positions.save(new Position("short-" + i, "seller-" + i, code, OptionType.CALL,
                    ExerciseStyle.AMERICAN, 90L, -10L, EXPIRY));
            h.accounts.save(new Account("buyer-" + i, 10_000_000L, 0L));
            h.accounts.save(new Account("seller-" + i, 10_000_000L, 0L));
            h.marketData.put(new SettlementPrice(code, 100L));
        }

        java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(n + 1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger manualSuccess = new AtomicInteger();
        AtomicInteger manualRejected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    h.instructions.requestExercise("long-" + index, true, "trader-" + index);
                    Position position = h.positions.findById("long-" + index).orElseThrow();
                    java.util.concurrent.locks.ReentrantLock contractLock =
                            h.lockManager.contractLock(position.getContractCode());
                    contractLock.lock();
                    try {
                        if (h.settlement.isPositionSettled(position.getId())) {
                            manualRejected.incrementAndGet();
                        } else {
                            List<Position> shorts = h.positions.findByContractCode(
                                    position.getContractCode()).stream()
                                    .filter(Position::isShort)
                                    .toList();
                            List<Assignment> assignments =
                                    new ProportionalAssignmentStrategy().allocate(
                                            position.getContractCode(), 10L, shorts, index);
                            h.settlement.settleManualExercise(position.getId(), position, 100L, assignments);
                            manualSuccess.incrementAndGet();
                        }
                    } finally {
                        contractLock.unlock();
                    }
                    h.instructions.completeManualExercise(position.getId());
                } catch (RuntimeException e) {
                    manualRejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        barrier.await();
        var runResult = h.engine.processExpiration(EXPIRY);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long totalLongRemaining = 0L;
        long totalShortRemaining = 0L;
        for (int i = 0; i < n; i++) {
            long longQty = h.positions.findById("long-" + i).orElseThrow().getQuantity();
            long shortQty = -h.positions.findById("short-" + i).orElseThrow().getQuantity();
            assertEquals(longQty, shortQty, "long and short quantities must match for " + i);
            totalLongRemaining += longQty;
            totalShortRemaining += shortQty;
        }
        assertEquals(totalLongRemaining, totalShortRemaining);
        assertEquals(n, runResult.exercised() + manualSuccess.get(),
                "each position must settle exactly once");
        assertEquals(n, manualSuccess.get() + manualRejected.get());
        assertEquals(0, totalLongRemaining, "every long must be settled once");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void repeatedSettlementCallsAreIdempotent() {
        Harness h = new Harness(tempDir);
        h.positions.save(new Position("long-1", "buyer", "CALL-1", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, 5L, EXPIRY));
        h.positions.save(new Position("short-1", "seller", "CALL-1", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -5L, EXPIRY));
        h.accounts.save(new Account("buyer", 1_000_000L, 0L));
        h.accounts.save(new Account("seller", 1_000_000L, 0L));
        Position longPosition = h.positions.findById("long-1").orElseThrow();
        List<Assignment> assignments = List.of(new Assignment("short-1", "seller", "CALL-1", 5L));

        for (int i = 0; i < 10; i++) {
            h.settlement.settleLongExercise("batch", longPosition, 100L, assignments);
        }

        assertEquals(0L, h.positions.findById("long-1").orElseThrow().getQuantity());
        assertEquals(0L, h.positions.findById("short-1").orElseThrow().getQuantity());
        assertEquals(5L, h.accounts.findById("buyer").orElseThrow().getUnderlyingUnits());
    }
}
