package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.optionsexerciseenginejava.domain.Account;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import com.github.guoyyit.optionsexerciseenginejava.repository.AccountRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryAccountRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryPositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.settlement.AtomicSettlementService;
import com.github.guoyyit.optionsexerciseenginejava.settlement.ContractLockManager;
import com.github.guoyyit.optionsexerciseenginejava.settlement.SettlementService;
import com.github.guoyyit.optionsexerciseenginejava.state.FileEngineStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementRecoveryTest {

    @TempDir
    Path tempDir;

    private EngineProperties properties(Path dir) {
        EngineProperties properties = new EngineProperties();
        properties.setStateDir(dir.toString());
        return properties;
    }

    private PositionRepository newRepositoriesWithPositions(AccountRepository accounts) {
        PositionRepository positions = new InMemoryPositionRepository();
        positions.save(new Position("long-1", "buyer", "CALL-OPT", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, 10L, 20260101L));
        positions.save(new Position("short-1", "seller-a", "CALL-OPT", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -6L, 20260101L));
        positions.save(new Position("short-2", "seller-b", "CALL-OPT", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -4L, 20260101L));
        accounts.save(new Account("buyer", 100_000L, 0L));
        accounts.save(new Account("seller-a", 100_000L, 0L));
        accounts.save(new Account("seller-b", 100_000L, 0L));
        return positions;
    }

    private List<Assignment> assignments() {
        return List.of(
                new Assignment("short-1", "seller-a", "CALL-OPT", 6L),
                new Assignment("short-2", "seller-b", "CALL-OPT", 4L));
    }

    @Test
    void exerciseSettlementIsAtomicAcrossAllParties() {
        AccountRepository accounts = new InMemoryAccountRepository();
        PositionRepository positions = newRepositoriesWithPositions(accounts);
        FileEngineStateStore store = new FileEngineStateStore(properties(tempDir), new ObjectMapper().findAndRegisterModules());
        SettlementService service = new AtomicSettlementService(positions, accounts, store, new ContractLockManager());

        Position longPosition = positions.findById("long-1").orElseThrow();
        service.settleLongExercise("batch-1", longPosition, 100L, assignments());

        assertEquals(0L, positions.findById("long-1").orElseThrow().getQuantity());
        assertEquals(0L, positions.findById("short-1").orElseThrow().getQuantity());
        assertEquals(0L, positions.findById("short-2").orElseThrow().getQuantity());
        assertEquals(100_000L - 1_000L, accounts.findById("buyer").orElseThrow().getCash());
        assertEquals(10L, accounts.findById("buyer").orElseThrow().getUnderlyingUnits());
        assertEquals(100_000L + 600L, accounts.findById("seller-a").orElseThrow().getCash());
        assertEquals(-6L, accounts.findById("seller-a").orElseThrow().getUnderlyingUnits());
    }

    @Test
    void recoveryReplaysCommittedTransactionsAndSkipsDuplicates() {
        AccountRepository accounts = new InMemoryAccountRepository();
        PositionRepository positions = newRepositoriesWithPositions(accounts);
        FileEngineStateStore store = new FileEngineStateStore(properties(tempDir), new ObjectMapper().findAndRegisterModules());
        SettlementService firstRun = new AtomicSettlementService(positions, accounts, store, new ContractLockManager());
        firstRun.settleLongExercise("batch-1", positions.findById("long-1").orElseThrow(),
                100L, assignments());

        AccountRepository rebuiltAccounts = new InMemoryAccountRepository();
        PositionRepository rebuiltPositions = newRepositoriesWithPositions(rebuiltAccounts);
        FileEngineStateStore reopenedStore =
                new FileEngineStateStore(properties(tempDir), new ObjectMapper().findAndRegisterModules());
        SettlementService recovered = new AtomicSettlementService(rebuiltPositions, rebuiltAccounts, reopenedStore, new ContractLockManager());
        recovered.recover();

        assertEquals(0L, rebuiltPositions.findById("long-1").orElseThrow().getQuantity());
        assertEquals(10L, rebuiltAccounts.findById("buyer").orElseThrow().getUnderlyingUnits());
        assertTrue(recovered.isPositionSettled("long-1"));

        recovered.settleLongExercise("batch-1", rebuiltPositions.findById("long-1").orElseThrow(),
                100L, assignments());
        assertEquals(10L, rebuiltAccounts.findById("buyer").orElseThrow().getUnderlyingUnits());
    }

    @Test
    void uncommittedInterruptedTransactionIsRolledBackOnRecovery() {
        AccountRepository accounts = new InMemoryAccountRepository();
        PositionRepository positions = newRepositoriesWithPositions(accounts);
        FileEngineStateStore store = new FileEngineStateStore(properties(tempDir), new ObjectMapper().findAndRegisterModules());
        store.beginTransaction("orphan-tx", "exercise:orphan");
        store.appendEvent("orphan-tx",
                new com.github.guoyyit.optionsexerciseenginejava.domain.SettlementEvent(
                        0L, "exercise:orphan",
                        com.github.guoyyit.optionsexerciseenginejava.domain.SettlementEventType.EXERCISE,
                        "buyer", "long-1", "CALL-OPT", -10L, -1_000L, 10L, null, "orphan"));

        SettlementService recovered = new AtomicSettlementService(positions, accounts, store, new ContractLockManager());
        recovered.recover();

        assertEquals(10L, positions.findById("long-1").orElseThrow().getQuantity());
        assertEquals(100_000L, accounts.findById("buyer").orElseThrow().getCash());
        assertTrue(store.pendingTransactions().isEmpty());
    }

    @Test
    void validationFailureAppliesNothingAndAllowsRetry() {
        AccountRepository accounts = new InMemoryAccountRepository();
        PositionRepository positions = newRepositoriesWithPositions(accounts);
        FileEngineStateStore store = new FileEngineStateStore(properties(tempDir), new ObjectMapper().findAndRegisterModules());
        SettlementService service = new AtomicSettlementService(positions, accounts, store, new ContractLockManager());

        List<Assignment> wrong = List.of(
                new Assignment("short-1", "seller-a", "CALL-OPT", 9L),
                new Assignment("short-2", "seller-b", "CALL-OPT", 4L));
        assertThrows(IllegalArgumentException.class,
                () -> service.settleLongExercise("batch-1",
                        positions.findById("long-1").orElseThrow(), 100L, wrong));

        assertEquals(10L, positions.findById("long-1").orElseThrow().getQuantity());
        service.settleLongExercise("batch-1", positions.findById("long-1").orElseThrow(),
                100L, assignments());
        assertEquals(0L, positions.findById("long-1").orElseThrow().getQuantity());
    }
}
