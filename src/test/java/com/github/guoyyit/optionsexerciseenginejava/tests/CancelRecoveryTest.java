package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.optionsexerciseenginejava.assignment.ProportionalAssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.assignment.RandomAssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.Account;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelRecoveryTest {

    static final long EXPIRY = 20260101L;

    @TempDir
    Path tempDir;

    private Harness harness(int contracts, int batchSize, int concurrency, long timeoutMs) {
        Harness h = new Harness();
        h.properties = new EngineProperties();
        h.properties.setStateDir(tempDir.toString());
        h.properties.setBatchSize(batchSize);
        h.properties.setConcurrency(concurrency);
        h.properties.setOperationTimeoutMs(timeoutMs);
        h.positions = new InMemoryPositionRepository();
        h.accounts = new InMemoryAccountRepository();
        h.marketData = new InMemoryMarketDataProvider();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileEngineStateStore stateStore = new FileEngineStateStore(h.properties, objectMapper);
        h.lockManager = new ContractLockManager();
        h.settlement = new AtomicSettlementService(h.positions, h.accounts, stateStore, h.lockManager);
        h.instructions = new DefaultExerciseInstructionService(h.positions);
        DefaultExpirationScanner scanner = new DefaultExpirationScanner(h.positions, h.marketData,
                new DefaultExerciseDecisionService(), h.instructions, h.properties);
        h.batchExecutor = new ConfigurableBatchExecutor(h.properties);
        FileBackedAuditLog auditLog = new FileBackedAuditLog(h.properties, objectMapper);
        DefaultInterventionService intervention = new DefaultInterventionService(
                new SimplePermissionService(), auditLog, h.settlement, h.instructions, h.properties);
        h.engine = new DefaultExpirationEngine(scanner, h.batchExecutor, h.settlement, h.positions,
                new ProportionalAssignmentStrategy(), new RandomAssignmentStrategy(h.properties),
                h.properties, auditLog, intervention, h.lockManager);
        for (int i = 0; i < contracts; i++) {
            String code = "CALL-" + i;
            h.positions.save(new Position("long-" + i, "buyer-" + i, code, OptionType.CALL,
                    ExerciseStyle.EUROPEAN, 90L, 4L, EXPIRY));
            h.positions.save(new Position("short-" + i, "seller-" + i, code, OptionType.CALL,
                    ExerciseStyle.EUROPEAN, 90L, -4L, EXPIRY));
            h.accounts.save(new Account("buyer-" + i, 10_000_000L, 0L));
            h.accounts.save(new Account("seller-" + i, 10_000_000L, 0L));
            h.marketData.put(new SettlementPrice(code, 100L));
        }
        return h;
    }

    private long unsettledLongs(Harness h, int contracts) {
        long remaining = 0L;
        for (int i = 0; i < contracts; i++) {
            remaining += h.positions.findById("long-" + i).orElseThrow().getQuantity();
        }
        return remaining;
    }

    @Test
    void cancellationLeavesNoIntermediateStateAndResumeCompletesAll() throws Exception {
        int contracts = 30;
        Harness h = harness(contracts, 1, 1, 0L);

        Thread cancelLater = new Thread(() -> {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            h.engine.cancel();
        });
        cancelLater.start();
        var first = h.engine.processExpiration(EXPIRY);
        cancelLater.join();

        assertTrue(first.cancelled() || first.exercised() < contracts);
        long remaining = unsettledLongs(h, contracts);
        long remainingShorts = 0L;
        for (int i = 0; i < contracts; i++) {
            long longQty = h.positions.findById("long-" + i).orElseThrow().getQuantity();
            long shortQty = -h.positions.findById("short-" + i).orElseThrow().getQuantity();
            assertEquals(longQty, shortQty, "no half-settled contract: " + i);
            remainingShorts += shortQty;
        }
        assertEquals(remaining, remainingShorts);

        var resumed = h.engine.processExpiration(EXPIRY);
        assertEquals(0L, unsettledLongs(h, contracts));
        assertEquals(first.exercised() + resumed.exercised(), contracts,
                "resume continues from last consistent state without duplicates");
    }

    static class Harness {
        EngineProperties properties;
        PositionRepository positions;
        AccountRepository accounts;
        InMemoryMarketDataProvider marketData;
        ContractLockManager lockManager;
        AtomicSettlementService settlement;
        DefaultExerciseInstructionService instructions;
        ConfigurableBatchExecutor batchExecutor;
        DefaultExpirationEngine engine;
    }
}
