package com.github.guoyyit.optionsexerciseenginejava.api;

import com.github.guoyyit.optionsexerciseenginejava.assignment.AssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.engine.ExpirationEngine;
import com.github.guoyyit.optionsexerciseenginejava.exercise.ExerciseInstructionService;
import com.github.guoyyit.optionsexerciseenginejava.intervention.AuditEntry;
import com.github.guoyyit.optionsexerciseenginejava.intervention.AuditLog;
import com.github.guoyyit.optionsexerciseenginejava.intervention.InterventionService;
import com.github.guoyyit.optionsexerciseenginejava.market.MarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.settlement.SettlementService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/exercise")
public class ExerciseController {

    private final ExpirationEngine expirationEngine;
    private final ExerciseInstructionService exerciseInstructionService;
    private final InterventionService interventionService;
    private final AuditLog auditLog;
    private final SettlementService settlementService;
    private final PositionRepository positionRepository;
    private final MarketDataProvider marketDataProvider;
    private final AssignmentStrategy proportionalStrategy;
    private final AssignmentStrategy randomStrategy;

    public ExerciseController(ExpirationEngine expirationEngine,
                              ExerciseInstructionService exerciseInstructionService,
                              InterventionService interventionService,
                              AuditLog auditLog,
                              SettlementService settlementService,
                              PositionRepository positionRepository,
                              MarketDataProvider marketDataProvider,
                              @Qualifier("proportionalAssignmentStrategy") AssignmentStrategy proportionalStrategy,
                              @Qualifier("randomAssignmentStrategy") AssignmentStrategy randomStrategy) {
        this.expirationEngine = expirationEngine;
        this.exerciseInstructionService = exerciseInstructionService;
        this.interventionService = interventionService;
        this.auditLog = auditLog;
        this.settlementService = settlementService;
        this.positionRepository = positionRepository;
        this.marketDataProvider = marketDataProvider;
        this.proportionalStrategy = proportionalStrategy;
        this.randomStrategy = randomStrategy;
    }

    @PostMapping("/instruction/{positionId}")
    public String instruction(@PathVariable String positionId,
                              @RequestParam(defaultValue = "false") boolean override,
                              @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator) {
        exerciseInstructionService.requestExercise(positionId, override, operator);
        return "instruction accepted: " + positionId;
    }

    @PostMapping("/manual-exercise/{positionId}")
    public String manualExercise(@PathVariable String positionId,
                                 @RequestParam(defaultValue = "proportional") String strategy,
                                 @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("position not found: " + positionId));
        if (!exerciseInstructionService.isManualExerciseRequested(positionId)) {
            throw new IllegalStateException("no pending exercise instruction: " + positionId);
        }
        long price = marketDataProvider.settlementPrice(position.getContractCode())
                .orElseThrow(() -> new IllegalStateException(
                        "settlement price unavailable: " + position.getContractCode()))
                .getPrice();
        List<Position> shorts = positionRepository.findByContractCode(position.getContractCode()).stream()
                .filter(Position::isShort)
                .toList();
        AssignmentStrategy assignmentStrategy = "random".equalsIgnoreCase(strategy)
                ? randomStrategy : proportionalStrategy;
        List<Assignment> assignments = assignmentStrategy.allocate(
                position.getContractCode(), position.getQuantity(), shorts,
                (long) ("manual:" + positionId).hashCode());
        settlementService.settleManualExercise(positionId, position, price, assignments);
        exerciseInstructionService.completeManualExercise(positionId);
        auditLog.record(operator, "MANUAL_EXERCISE", positionId,
                "manual exercise settled with " + assignments.size() + " assignments", true, "manual");
        return "manual exercise settled: " + positionId;
    }

    @PostMapping("/revoke/{positionId}")
    public String revoke(@PathVariable String positionId,
                         @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator) {
        interventionService.revokeExercise(positionId, operator);
        return "instruction revoked: " + positionId;
    }

    @PostMapping("/adjust")
    public String adjust(@RequestBody AdjustAssignmentRequest request,
                         @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator) {
        interventionService.adjustAssignment(request.referenceId(), request.from(), request.to(), operator);
        return "assignment adjusted: " + request.referenceId();
    }

    @PostMapping("/expire/{expiryDate}")
    public ExpirationEngine.EngineRunResult expire(@PathVariable long expiryDate) {
        return expirationEngine.processExpiration(expiryDate);
    }

    @PostMapping("/cancel")
    public String cancel() {
        expirationEngine.cancel();
        return "cancellation requested";
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(required = false) String target) {
        return target == null ? auditLog.findAll() : auditLog.findByTarget(target);
    }

    public record AdjustAssignmentRequest(String referenceId, Assignment from, Assignment to) {
    }
}
