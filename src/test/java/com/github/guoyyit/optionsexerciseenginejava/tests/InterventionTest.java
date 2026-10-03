package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.optionsexerciseenginejava.domain.Account;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import com.github.guoyyit.optionsexerciseenginejava.exercise.DefaultExerciseInstructionService;
import com.github.guoyyit.optionsexerciseenginejava.intervention.AuditEntry;
import com.github.guoyyit.optionsexerciseenginejava.intervention.DefaultInterventionService;
import com.github.guoyyit.optionsexerciseenginejava.intervention.FileBackedAuditLog;
import com.github.guoyyit.optionsexerciseenginejava.intervention.PermissionService;
import com.github.guoyyit.optionsexerciseenginejava.intervention.SimplePermissionService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterventionTest {

    static final long EXPIRY = 20260101L;

    @TempDir
    Path tempDir;

    EngineProperties properties;
    PositionRepository positions;
    AccountRepository accounts;
    AtomicSettlementService settlement;
    DefaultExerciseInstructionService instructions;
    SimplePermissionService permissions;
    DefaultInterventionService intervention;
    FileBackedAuditLog auditLog;

    @BeforeEach
    void setUp() {
        properties = new EngineProperties();
        properties.setStateDir(tempDir.toString());
        positions = new InMemoryPositionRepository();
        accounts = new InMemoryAccountRepository();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileEngineStateStore store = new FileEngineStateStore(properties, objectMapper);
        settlement = new AtomicSettlementService(positions, accounts, store, new ContractLockManager());
        instructions = new DefaultExerciseInstructionService(positions);
        permissions = new SimplePermissionService();
        auditLog = new FileBackedAuditLog(properties, objectMapper);
        intervention = new DefaultInterventionService(
                permissions, auditLog, settlement, instructions, properties);
        intervention.openWindow();
    }

    @Test
    void revokeRequiresPermissionAndRemovesPendingInstruction() {
        positions.save(new Position("long-1", "buyer", "CALL-1", OptionType.CALL,
                ExerciseStyle.AMERICAN, 90L, 5L, EXPIRY));
        instructions.requestExercise("long-1", true, "trader");

        assertThrows(SecurityException.class, () -> intervention.revokeExercise("long-1", "clerk"));
        permissions.grant("supervisor", PermissionService.Permission.REVOKE_EXERCISE);
        intervention.revokeExercise("long-1", "supervisor");

        assertFalse(instructions.isManualExerciseRequested("long-1"));
        assertTrue(auditLog.findByTarget("long-1").stream()
                .anyMatch(e -> e.getAction().equals("REVOKE_EXERCISE") && e.isAllowed()));
    }

    @Test
    void revokedSettledPositionIsRejectedAndAudited() {
        settleOne();
        permissions.grant("supervisor", PermissionService.Permission.REVOKE_EXERCISE);

        assertThrows(IllegalStateException.class,
                () -> intervention.revokeExercise("long-1", "supervisor"));
        AuditEntry denied = auditLog.findByTarget("long-1").stream()
                .filter(e -> e.getAction().equals("REVOKE_EXERCISE"))
                .reduce((a, b) -> b).orElseThrow();
        assertFalse(denied.isAllowed());
    }

    @Test
    void adjustAssignmentMovesShortQuantityAtomicallyAndIsAudited() {
        settleOne();
        permissions.grant("ops", PermissionService.Permission.ADJUST_ASSIGNMENT);
        Assignment from = new Assignment("short-1", "seller-a", "CALL-1", 3L);
        Assignment to = new Assignment("short-2", "seller-b", "CALL-1", 3L);

        intervention.adjustAssignment("ref-1", from, to, "ops");

        assertEquals(3L, positions.findById("short-1").orElseThrow().getQuantity());
        assertEquals(-3L, positions.findById("short-2").orElseThrow().getQuantity());
        assertTrue(auditLog.findByTarget("ref-1").stream()
                .anyMatch(e -> e.getAction().equals("ADJUST_ASSIGNMENT") && e.isAllowed()));
    }

    @Test
    void adjustmentWithoutPermissionIsRejected() {
        settleOne();
        Assignment from = new Assignment("short-1", "seller-a", "CALL-1", 3L);
        Assignment to = new Assignment("short-2", "seller-b", "CALL-1", 3L);

        assertThrows(SecurityException.class,
                () -> intervention.adjustAssignment("ref-2", from, to, "nobody"));
        assertEquals(0L, positions.findById("short-1").orElseThrow().getQuantity());
    }

    @Test
    void interventionWindowClosesAfterDeadline() {
        properties.setInterventionWindowMs(1L);
        intervention.openWindow();
        permissions.grant("ops", PermissionService.Permission.ADJUST_ASSIGNMENT);
        settleOne();
        Assignment from = new Assignment("short-1", "seller-a", "CALL-1", 1L);
        Assignment to = new Assignment("short-2", "seller-b", "CALL-1", 1L);

        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThrows(IllegalStateException.class,
                () -> intervention.adjustAssignment("ref-3", from, to, "ops"));
    }

    private void settleOne() {
        positions.save(new Position("long-1", "buyer", "CALL-1", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, 5L, EXPIRY));
        positions.save(new Position("short-1", "seller-a", "CALL-1", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -3L, EXPIRY));
        positions.save(new Position("short-2", "seller-b", "CALL-1", OptionType.CALL,
                ExerciseStyle.EUROPEAN, 90L, -2L, EXPIRY));
        accounts.save(new Account("buyer", 1_000_000L, 0L));
        accounts.save(new Account("seller-a", 1_000_000L, 0L));
        accounts.save(new Account("seller-b", 1_000_000L, 0L));
        List<Assignment> assignments = List.of(
                new Assignment("short-1", "seller-a", "CALL-1", 3L),
                new Assignment("short-2", "seller-b", "CALL-1", 2L));
        settlement.settleLongExercise("batch", positions.findById("long-1").orElseThrow(),
                100L, assignments);
    }
}
