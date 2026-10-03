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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpirationEngineTest {

    static final long EXPIRY = 20260101L;

    @TempDir
    Path tempDir;

    EngineProperties properties;
    PositionRepository positions;
    AccountRepository accounts;
    InMemoryMarketDataProvider marketData;
    DefaultExpirationEngine engine;
    FileBackedAuditLog auditLog;
    AtomicSettlementService settlementService;

    @BeforeEach
    void setUp() {
        properties = new EngineProperties();
        properties.setStateDir(tempDir.toString());
        properties.setBatchSize(2);
        properties.setConcurrency(2);
        properties.setExerciseThreshold(3L);
        positions = new InMemoryPositionRepository();
        accounts = new InMemoryAccountRepository();
        marketData = new InMemoryMarketDataProvider();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileEngineStateStore stateStore = new FileEngineStateStore(properties, objectMapper);
        ContractLockManager lockManager = new ContractLockManager();
        settlementService = new AtomicSettlementService(positions, accounts, stateStore, lockManager);
        DefaultExerciseInstructionService instructionService =
                new DefaultExerciseInstructionService(positions);
        DefaultExerciseDecisionService decisionService = new DefaultExerciseDecisionService();
        DefaultExpirationScanner scanner = new DefaultExpirationScanner(
                positions, marketData, decisionService, instructionService, properties);
        ConfigurableBatchExecutor batchExecutor = new ConfigurableBatchExecutor(properties);
        ProportionalAssignmentStrategy proportional = new ProportionalAssignmentStrategy();
        RandomAssignmentStrategy random = new RandomAssignmentStrategy(properties);
        auditLog = new FileBackedAuditLog(properties, objectMapper);
        SimplePermissionService permissions = new SimplePermissionService();
        DefaultInterventionService intervention = new DefaultInterventionService(
                permissions, auditLog, settlementService, instructionService, properties);
        engine = new DefaultExpirationEngine(scanner, batchExecutor, settlementService, positions,
                proportional, random, properties, auditLog, intervention, lockManager);
    }

    private void longPosition(String id, String contract, long strike, long qty) {
        positions.save(new Position(id, "acct-" + id, contract, OptionType.CALL,
                ExerciseStyle.EUROPEAN, strike, qty, EXPIRY));
        accounts.save(new Account("acct-" + id, 1_000_000L, 0L));
    }

    @Test
    void abandonsOtmAndThinItmExercisesDeepItm() {
        longPosition("long-otm", "CALL-OTM", 110L, 5L);
        longPosition("long-itm", "CALL-ITM", 90L, 8L);
        longPosition("long-thin", "CALL-THIN", 99L, 4L);
        positions.save(new Position("short-itm", "seller", "CALL-ITM", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -8L, EXPIRY));
        accounts.save(new Account("seller", 1_000_000L, 0L));
        marketData.put(new SettlementPrice("CALL-OTM", 100L));
        marketData.put(new SettlementPrice("CALL-ITM", 100L));
        marketData.put(new SettlementPrice("CALL-THIN", 100L));

        var result = engine.processExpiration(EXPIRY);

        assertEquals(3, result.scanned());
        assertEquals(1, result.exercised());
        assertEquals(2, result.abandoned());
        assertEquals(5L, positions.findById("long-otm").orElseThrow().getQuantity());
        assertEquals(0L, positions.findById("long-itm").orElseThrow().getQuantity());
        assertEquals(4L, positions.findById("long-thin").orElseThrow().getQuantity());
        assertEquals(0L, positions.findById("short-itm").orElseThrow().getQuantity());
        assertTrue(auditLog.findByTarget("long-otm").stream()
                .anyMatch(e -> e.getAction().equals("AUTO_ABANDON")));
        assertTrue(auditLog.findByTarget("long-thin").stream()
                .anyMatch(e -> e.getReason().contains("below threshold")));
    }

    @Test
    void rerunAfterCompletionIsIdempotent() {
        longPosition("long-itm", "CALL-ITM", 90L, 8L);
        positions.save(new Position("short-itm", "seller", "CALL-ITM", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -8L, EXPIRY));
        accounts.save(new Account("seller", 1_000_000L, 0L));
        marketData.put(new SettlementPrice("CALL-ITM", 100L));

        var first = engine.processExpiration(EXPIRY);
        var second = engine.processExpiration(EXPIRY);

        assertEquals(1, first.exercised());
        assertEquals(0, second.exercised());
        assertEquals(0L, positions.findById("long-itm").orElseThrow().getQuantity());
    }

    @Test
    void largeBatchIsFullyProcessedInBatches() {
        int contracts = 25;
        for (int i = 0; i < contracts; i++) {
            String code = "CALL-" + i;
            longPosition("long-" + i, code, 90L, 4L);
            positions.save(new Position("short-" + i, "seller-" + i, code, OptionType.CALL,
                    ExerciseStyle.EUROPEAN, 90L, -4L, EXPIRY));
            accounts.save(new Account("seller-" + i, 1_000_000L, 0L));
            marketData.put(new SettlementPrice(code, 100L));
        }

        var result = engine.processExpiration(EXPIRY);

        assertEquals(contracts, result.scanned());
        assertEquals(contracts, result.exercised());
        assertEquals(0, result.abandoned());
        for (int i = 0; i < contracts; i++) {
            assertEquals(0L, positions.findById("long-" + i).orElseThrow().getQuantity());
            assertEquals(0L, positions.findById("short-" + i).orElseThrow().getQuantity());
        }
    }
}
