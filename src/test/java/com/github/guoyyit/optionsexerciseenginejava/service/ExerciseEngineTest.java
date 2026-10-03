package com.github.guoyyit.optionsexerciseenginejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.AuditAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStatus;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Operator;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import com.github.guoyyit.optionsexerciseenginejava.exception.UnauthorizedInterventionException;
import com.github.guoyyit.optionsexerciseenginejava.market.ContractRepository;
import com.github.guoyyit.optionsexerciseenginejava.market.InMemoryContractRepository;
import com.github.guoyyit.optionsexerciseenginejava.market.InMemoryMarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.market.InMemoryPositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.market.MarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.market.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.AssignmentRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.AuditLogRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.ExerciseInstructionRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryAssignmentRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryAuditLogRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.InMemoryExerciseInstructionRepository;
import com.github.guoyyit.optionsexerciseenginejava.service.ExerciseService.ContractResult;
import com.github.guoyyit.optionsexerciseenginejava.service.SettlementService.SettlementResult;
import com.github.guoyyit.optionsexerciseenginejava.store.JsonStateStore;
import com.github.guoyyit.optionsexerciseenginejava.store.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseEngineTest {
    private static final LocalDate EXPIRY = LocalDate.now();
    private static final String CASH = "CASH";

    @TempDir
    Path tempDir;

    private ExerciseProperties properties;
    private InMemoryContractRepository contracts;
    private InMemoryPositionRepository positions;
    private InMemoryMarketDataProvider prices;
    private InMemoryExerciseInstructionRepository exercises;
    private InMemoryAssignmentRepository assignments;
    private InMemoryAuditLogRepository audits;
    private StateManager stateManager;
    private SettlementService settlementService;
    private ExerciseService exerciseService;
    private ExpirationProcessor expirationProcessor;
    private ManualInterventionService manualInterventionService;
    private ContractLockRegistry contractLockRegistry;

    @BeforeEach
    void setUp() {
        properties = new ExerciseProperties();
        properties.setStateFile(tempDir.resolve("state.json").toString());
        properties.setIntrinsicThreshold(BigDecimal.ZERO);
        properties.setAssignmentStrategy(AssignmentStrategy.PRO_RATA);
        properties.setRandomSeed(42L);
        properties.setBatchSize(2);
        properties.setConcurrency(2);
        properties.setProcessingTimeout(Duration.ofSeconds(5));
        properties.setCutoffHour(23);

        contracts = new InMemoryContractRepository();
        positions = new InMemoryPositionRepository();
        prices = new InMemoryMarketDataProvider();
        exercises = new InMemoryExerciseInstructionRepository();
        assignments = new InMemoryAssignmentRepository();
        audits = new InMemoryAuditLogRepository();
        contractLockRegistry = new ContractLockRegistry();
        stateManager = new StateManager(
                contracts,
                positions,
                prices,
                exercises,
                assignments,
                audits,
                new JsonStateStore(new ObjectMapper().registerModule(new JavaTimeModule()), properties)
        );
        stateManager.init();
        settlementService = new SettlementService(stateManager);
        exerciseService = new ExerciseService(
                contracts,
                positions,
                prices,
                exercises,
                assignments,
                audits,
                stateManager,
                settlementService,
                properties,
                Map.of(
                        "proRataAssignmentAllocator", new ProRataAssignmentAllocator(),
                        "randomAssignmentAllocator", new RandomAssignmentAllocator(42L)
                ),
                contractLockRegistry
        );
        expirationProcessor = new ExpirationProcessor(exerciseService, contracts, properties);
        manualInterventionService = new ManualInterventionService(stateManager, contracts, contractLockRegistry, properties);
    }

    @Test
    void abandonsOutOfTheMoneyPositionWithAudit() {
        Contract contract = callContract("OTM", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 10));
        positions.save(new Position("WRITER", contract.id(), -10));
        positions.save(new Position("LONG", CASH, 10_000));
        positions.save(new Position("WRITER", "ASSET", 10));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("99"), EXPIRY));

        ContractResult result = exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA);

        assertTrue(result.settled().isEmpty());
        assertEquals(1, result.abandoned().size());
        ExerciseInstruction abandoned = result.abandoned().get(0);
        assertEquals(ExerciseStatus.ABANDONED, abandoned.status());
        assertEquals("OUT_OF_THE_MONEY", abandoned.reason());
        assertEquals(1, audits.findAll().stream().filter(log -> log.action() == AuditAction.AUTO_ABANDON).count());
        assertEquals(10, positions.find("LONG", contract.id()).orElseThrow().quantity());
        assertEquals(-10, positions.find("WRITER", contract.id()).orElseThrow().quantity());
    }

    @Test
    void thresholdControlsExerciseDecision() {
        properties.setIntrinsicThreshold(new BigDecimal("5"));
        Contract belowContract = callContract("THRESHOLD-BELOW", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        Contract aboveContract = callContract("THRESHOLD-ABOVE", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(belowContract);
        contracts.save(aboveContract);
        positions.save(new Position("LONG", belowContract.id(), 10));
        positions.save(new Position("WRITER", belowContract.id(), -10));
        positions.save(new Position("LONG", aboveContract.id(), 10));
        positions.save(new Position("WRITER", aboveContract.id(), -10));
        positions.save(new Position("LONG", CASH, 10_000));
        positions.save(new Position("WRITER", "ASSET", 10));

        prices.save(new SettlementPrice(belowContract.id(), new BigDecimal("104"), EXPIRY));
        prices.save(new SettlementPrice(aboveContract.id(), new BigDecimal("105"), EXPIRY));
        ContractResult belowThreshold = exerciseService.processContract(belowContract, EXPIRY, AssignmentStrategy.PRO_RATA);
        assertEquals(1, belowThreshold.abandoned().size());
        assertEquals("BELOW_THRESHOLD", belowThreshold.abandoned().get(0).reason());

        ContractResult aboveThreshold = exerciseService.processContract(aboveContract, EXPIRY, AssignmentStrategy.PRO_RATA);
        assertEquals(1, aboveThreshold.settled().size());
        assertEquals(ExerciseStatus.SETTLED, aboveThreshold.settled().get(0).exercise().status());
    }

    @Test
    void allocatesProRataAndSettlesAtomically() {
        Contract contract = callContract("PRO", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 10));
        positions.save(new Position("A", contract.id(), -60));
        positions.save(new Position("B", contract.id(), -40));
        positions.save(new Position("LONG", CASH, 2_000));
        positions.save(new Position("A", "ASSET", 10));
        positions.save(new Position("B", "ASSET", 10));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));

        ContractResult result = exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA);

        assertEquals(1, result.settled().size());
        SettlementResult settled = result.settled().get(0);
        assertEquals(ExerciseStatus.SETTLED, settled.exercise().status());
        Map<String, Long> assigned = settled.assignments().stream()
                .collect(java.util.stream.Collectors.groupingBy(Assignment::writerAccountId, java.util.stream.Collectors.summingLong(Assignment::quantity)));
        assertEquals(6, assigned.get("A"));
        assertEquals(4, assigned.get("B"));
        assertEquals(0, positions.find("LONG", contract.id()).map(Position::quantity).orElse(0L));
        assertEquals(-54, positions.find("A", contract.id()).map(Position::quantity).orElse(0L));
        assertEquals(-36, positions.find("B", contract.id()).map(Position::quantity).orElse(0L));
        assertEquals(1_000, positions.find("LONG", CASH).orElseThrow().quantity());
        assertEquals(10, positions.find("LONG", "ASSET").orElseThrow().quantity());
        assertEquals(600, positions.find("A", CASH).orElseThrow().quantity());
        assertEquals(4, positions.find("A", "ASSET").orElseThrow().quantity());
        assertEquals(400, positions.find("B", CASH).orElseThrow().quantity());
        assertEquals(6, positions.find("B", "ASSET").orElseThrow().quantity());
    }

    @Test
    void randomAllocationIsDeterministicForFixedSeed() {
        Contract contract = callContract("RANDOM", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        List<Position> shorts = List.of(
                new Position("A", contract.id(), -100),
                new Position("B", contract.id(), -100),
                new Position("C", contract.id(), -100)
        );
        RandomAssignmentAllocator first = new RandomAssignmentAllocator(42L);
        RandomAssignmentAllocator second = new RandomAssignmentAllocator(42L);

        List<Assignment> one = first.allocate(contract, shorts, 10, "EX-1");
        List<Assignment> two = second.allocate(contract, shorts, 10, "EX-1");

        assertEquals(assignmentQuantities(one), assignmentQuantities(two));
        assertEquals(10, one.stream().mapToLong(Assignment::quantity).sum());
    }

    @Test
    void randomStrategySettlesWithFixedSeed() {
        Contract contract = callContract("RANDOM-SERVICE", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 10));
        positions.save(new Position("A", contract.id(), -60));
        positions.save(new Position("B", contract.id(), -40));
        positions.save(new Position("LONG", CASH, 2_000));
        positions.save(new Position("A", "ASSET", 10));
        positions.save(new Position("B", "ASSET", 10));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));

        ContractResult result = exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.RANDOM);

        assertEquals(1, result.settled().size());
        SettlementResult settled = result.settled().get(0);
        assertEquals(ExerciseStatus.SETTLED, settled.exercise().status());
        assertEquals(10, settled.assignments().stream().mapToLong(Assignment::quantity).sum());
        assertTrue(settled.assignments().stream().allMatch(assignment -> assignment.status() == AssignmentStatus.SETTLED));
    }

    @Test
    void failedSettlementLeavesStateUnchanged() {
        Contract contract = callContract("FAIL", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 10));
        positions.save(new Position("WRITER", contract.id(), -10));
        positions.save(new Position("LONG", CASH, 0));
        positions.save(new Position("WRITER", "ASSET", 10));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));
        StateSnapshot before = stateManager.snapshot();

        assertThrows(RuntimeException.class, () -> exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA));

        assertEquals(before, stateManager.snapshot());
        assertTrue(exercises.findAll().isEmpty());
        assertTrue(assignments.findAll().isEmpty());
    }

    @Test
    void recoversFromFileWithoutDuplicateSettlement() {
        Contract contract = callContract("RECOVER", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 10));
        positions.save(new Position("WRITER", contract.id(), -10));
        positions.save(new Position("LONG", CASH, 2_000));
        positions.save(new Position("WRITER", "ASSET", 10));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));
        exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA);

        InMemoryContractRepository restoredContracts = new InMemoryContractRepository();
        InMemoryPositionRepository restoredPositions = new InMemoryPositionRepository();
        InMemoryMarketDataProvider restoredPrices = new InMemoryMarketDataProvider();
        InMemoryExerciseInstructionRepository restoredExercises = new InMemoryExerciseInstructionRepository();
        InMemoryAssignmentRepository restoredAssignments = new InMemoryAssignmentRepository();
        InMemoryAuditLogRepository restoredAudits = new InMemoryAuditLogRepository();
        StateManager restoredStateManager = new StateManager(
                restoredContracts,
                restoredPositions,
                restoredPrices,
                restoredExercises,
                restoredAssignments,
                restoredAudits,
                new JsonStateStore(new ObjectMapper().registerModule(new JavaTimeModule()), properties)
        );
        restoredStateManager.init();
        assertEquals(stateManager.snapshot(), restoredStateManager.snapshot());

        ExerciseService restoredExerciseService = new ExerciseService(
                restoredContracts,
                restoredPositions,
                restoredPrices,
                restoredExercises,
                restoredAssignments,
                restoredAudits,
                restoredStateManager,
                new SettlementService(restoredStateManager),
                properties,
                Map.of(
                        "proRataAssignmentAllocator", new ProRataAssignmentAllocator(),
                        "randomAssignmentAllocator", new RandomAssignmentAllocator(42L)
                ),
                new ContractLockRegistry()
        );
        ContractResult retry = restoredExerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA);
        assertTrue(retry.settled().isEmpty());
        assertTrue(retry.abandoned().isEmpty());
        assertEquals(1, restoredExercises.findAll().size());
        assertEquals(1, restoredAssignments.findAll().size());
        assertEquals(0, restoredPositions.find("LONG", contract.id()).map(Position::quantity).orElse(0L));
    }

    @Test
    void revokesPendingExerciseAndAdjustsAssignmentWithAudit() {
        Contract contract = callContract("MANUAL", ExerciseStyle.AMERICAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 2));
        positions.save(new Position("A", contract.id(), -2));
        positions.save(new Position("B", contract.id(), -2));
        positions.save(new Position("LONG", CASH, 2_000));
        positions.save(new Position("A", "ASSET", 2));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));

        SettlementResult pending = exerciseService.exerciseAmerican(
                contract.id(), "LONG", 2, "IDEM-1", "TRADER");
        assertEquals(ExerciseStatus.PENDING, pending.exercise().status());
        Assignment assignment = pending.assignments().get(0);
        assertEquals("A", assignment.writerAccountId());

        assertThrows(UnauthorizedInterventionException.class, () -> manualInterventionService.revokeExercise(
                pending.exercise().id(), "no permission", new Operator("NOBODY", java.util.Set.of())));

        ExerciseInstruction revoked = manualInterventionService.revokeExercise(
                pending.exercise().id(), "trader request", new Operator("ADMIN-1", java.util.Set.of("ADMIN")));
        assertEquals(ExerciseStatus.CANCELLED, revoked.status());
        assertEquals(AssignmentStatus.CANCELLED, assignments.findById(assignment.id()).orElseThrow().status());
        assertTrue(audits.findAll().stream().anyMatch(log -> log.action() == AuditAction.REVOKE_EXERCISE));

        SettlementResult pendingForAdjust = exerciseService.exerciseAmerican(
                contract.id(), "LONG", 2, "IDEM-2", "TRADER");
        Assignment assignmentForAdjust = pendingForAdjust.assignments().get(0);
        Assignment adjusted = manualInterventionService.adjustAssignment(
                assignmentForAdjust.id(), "B", "rebalance", new Operator("ADMIN-1", java.util.Set.of("ADMIN")));
        assertEquals("B", adjusted.writerAccountId());
        assertTrue(audits.findAll().stream().anyMatch(log -> log.action() == AuditAction.ADJUST_ASSIGNMENT));
    }

    @Test
    void processesLargeBatchInConfiguredBatches() {
        for (int i = 0; i < 10; i++) {
            Contract contract = callContract("BATCH-" + i, ExerciseStyle.EUROPEAN, new BigDecimal("100"));
            contracts.save(contract);
            positions.save(new Position("LONG-" + i, contract.id(), 1));
            positions.save(new Position("WRITER-" + i, contract.id(), -1));
            positions.save(new Position("LONG-" + i, CASH, 2_000));
            positions.save(new Position("WRITER-" + i, "ASSET", 1));
            prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));
        }

        ExpirationProcessor.ProcessingResult result = expirationProcessor.process(EXPIRY);

        assertFalse(result.timedOut());
        assertFalse(result.cancelled());
        assertEquals(10, result.results().size());
        assertTrue(result.failures().isEmpty());
        assertEquals(10, exercises.findAll().stream().filter(item -> item.status() == ExerciseStatus.SETTLED).count());
    }

    @Test
    void concurrentProcessingSettlesContractOnce() throws Exception {
        Contract contract = callContract("CONCURRENT", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 5));
        positions.save(new Position("A", contract.id(), -3));
        positions.save(new Position("B", contract.id(), -2));
        positions.save(new Position("LONG", CASH, 2_000));
        positions.save(new Position("A", "ASSET", 3));
        positions.save(new Position("B", "ASSET", 2));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ContractResult> task = () -> exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA);
            List<Future<ContractResult>> futures = executor.invokeAll(List.of(task, task));
            List<ContractResult> results = new ArrayList<>();
            for (Future<ContractResult> future : futures) {
                results.add(future.get());
            }
            long settled = results.stream().filter(result -> !result.settled().isEmpty()).count();
            assertEquals(1, settled);
            assertEquals(5, exercises.findAll().stream().filter(item -> item.status() == ExerciseStatus.SETTLED).mapToLong(ExerciseInstruction::quantity).sum());
            assertEquals(0, positions.find("LONG", contract.id()).map(Position::quantity).orElse(0L));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void manualExerciseAndAutoProcessSettleOnceWhenConcurrent() throws Exception {
        Contract contract = callContract("MANUAL-AUTO", ExerciseStyle.AMERICAN, new BigDecimal("100"));
        contracts.save(contract);
        positions.save(new Position("LONG", contract.id(), 5));
        positions.save(new Position("A", contract.id(), -3));
        positions.save(new Position("B", contract.id(), -2));
        positions.save(new Position("LONG", CASH, 2_000));
        positions.save(new Position("A", "ASSET", 3));
        positions.save(new Position("B", "ASSET", 2));
        prices.save(new SettlementPrice(contract.id(), new BigDecimal("110"), EXPIRY));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> tasks = List.of(
                    () -> {
                        try {
                            exerciseService.exerciseAmerican(contract.id(), "LONG", 5, "CONCURRENT-IDEM", "TRADER");
                        } catch (RuntimeException expected) {
                            // Auto processing may win; duplicate exercise is rejected.
                        }
                        return null;
                    },
                    () -> {
                        exerciseService.processContract(contract, EXPIRY, AssignmentStrategy.PRO_RATA);
                        return null;
                    }
            );
            executor.invokeAll(tasks);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, exercises.findAll().size());
        assertEquals(ExerciseStatus.SETTLED, exercises.findAll().get(0).status());
        assertEquals(5, exercises.findAll().get(0).quantity());
        assertEquals(2, assignments.findAll().size());
        assertEquals(5, assignments.findAll().stream().mapToLong(Assignment::quantity).sum());
        assertEquals(2, assignments.findAll().stream().map(Assignment::writerAccountId).distinct().count());
        assertEquals(0, positions.find("LONG", contract.id()).map(Position::quantity).orElse(0L));
    }

    @Test
    void timeoutCancelsProcessingWithoutIntermediateState() {
        Contract contract = callContract("SLOW", ExerciseStyle.EUROPEAN, new BigDecimal("100"));
        contracts.save(contract);
        for (int i = 0; i < 20; i++) {
            Contract item = callContract("SLOW-" + i, ExerciseStyle.EUROPEAN, new BigDecimal("100"));
            contracts.save(item);
            positions.save(new Position("LONG-" + i, item.id(), 1));
            positions.save(new Position("WRITER-" + i, item.id(), -1));
            positions.save(new Position("LONG-" + i, CASH, 2_000));
            positions.save(new Position("WRITER-" + i, "ASSET", 1));
            prices.save(new SettlementPrice(item.id(), new BigDecimal("110"), EXPIRY));
        }

        MarketDataProvider slowPrices = new MarketDataProvider() {
            @Override
            public Optional<SettlementPrice> getSettlementPrice(String contractId, LocalDate valuationDate) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return prices.getSettlementPrice(contractId, valuationDate);
            }

            @Override
            public List<SettlementPrice> findAllPrices() {
                return prices.findAllPrices();
            }

            @Override
            public void replaceAll(java.util.Collection<SettlementPrice> allPrices) {
                prices.replaceAll(allPrices);
            }
        };
        StateManager slowStateManager = new StateManager(
                contracts,
                positions,
                slowPrices,
                exercises,
                assignments,
                audits,
                new JsonStateStore(new ObjectMapper().registerModule(new JavaTimeModule()), properties)
        );
        SettlementService slowSettlementService = new SettlementService(slowStateManager);
        ExerciseService slowExerciseService = new ExerciseService(
                contracts,
                positions,
                slowPrices,
                exercises,
                assignments,
                audits,
                slowStateManager,
                slowSettlementService,
                properties,
                Map.of(
                        "proRataAssignmentAllocator", new ProRataAssignmentAllocator(),
                        "randomAssignmentAllocator", new RandomAssignmentAllocator(42L)
                ),
                new ContractLockRegistry()
        );
        ExpirationProcessor slowProcessor = new ExpirationProcessor(slowExerciseService, contracts, properties);
        properties.setProcessingTimeout(Duration.ofMillis(1));

        ExpirationProcessor.ProcessingResult result = slowProcessor.process(EXPIRY);

        assertTrue(result.timedOut());
        assertTrue(exercises.findAll().stream().noneMatch(item -> item.status() == ExerciseStatus.PENDING));
        assertTrue(assignments.findAll().stream().noneMatch(item -> item.status() == AssignmentStatus.PENDING));
    }

    private Contract callContract(String id, ExerciseStyle style, BigDecimal strike) {
        return new Contract(id, "ASSET", OptionType.CALL, style, strike, EXPIRY);
    }

    private Map<String, Long> assignmentQuantities(List<Assignment> assignments) {
        return assignments.stream()
                .collect(java.util.stream.Collectors.groupingBy(Assignment::writerAccountId, java.util.stream.Collectors.summingLong(Assignment::quantity)));
    }
}
