package com.github.guoyyit.optionsexerciseenginejava.web;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;
import com.github.guoyyit.optionsexerciseenginejava.domain.Operator;
import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import com.github.guoyyit.optionsexerciseenginejava.service.ExerciseService;
import com.github.guoyyit.optionsexerciseenginejava.service.ExpirationProcessor;
import com.github.guoyyit.optionsexerciseenginejava.service.ManualInterventionService;
import com.github.guoyyit.optionsexerciseenginejava.service.SettlementService.SettlementResult;
import com.github.guoyyit.optionsexerciseenginejava.store.StateManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ExerciseController {
    private final ExerciseService exerciseService;
    private final ExpirationProcessor expirationProcessor;
    private final ManualInterventionService manualInterventionService;
    private final StateManager stateManager;

    public ExerciseController(
            ExerciseService exerciseService,
            ExpirationProcessor expirationProcessor,
            ManualInterventionService manualInterventionService,
            StateManager stateManager
    ) {
        this.exerciseService = exerciseService;
        this.expirationProcessor = expirationProcessor;
        this.manualInterventionService = manualInterventionService;
        this.stateManager = stateManager;
    }

    @PostMapping("/expiration/process")
    public ExpirationProcessor.ProcessingResult processExpiration(@RequestParam LocalDate date) {
        return expirationProcessor.process(date);
    }

    @PostMapping("/exercises/american")
    public SettlementResult exerciseAmerican(@RequestBody AmericanExerciseRequest request) {
        return exerciseService.exerciseAmerican(
                request.contractId(),
                request.accountId(),
                request.quantity(),
                request.idempotencyKey(),
                request.operatorId()
        );
    }

    @PostMapping("/interventions/revoke")
    public ExerciseInstruction revoke(@RequestBody RevokeRequest request) {
        return manualInterventionService.revokeExercise(
                request.instructionId(),
                request.reason(),
                new Operator(request.operatorId(), request.roles())
        );
    }

    @PostMapping("/interventions/adjust")
    public Assignment adjust(@RequestBody AdjustRequest request) {
        return manualInterventionService.adjustAssignment(
                request.assignmentId(),
                request.newWriterAccountId(),
                request.reason(),
                new Operator(request.operatorId(), request.roles())
        );
    }

    @GetMapping("/state")
    public StateSnapshot state() {
        return stateManager.snapshot();
    }

    public record AmericanExerciseRequest(
            String contractId,
            String accountId,
            long quantity,
            String idempotencyKey,
            String operatorId
    ) {
    }

    public record RevokeRequest(String instructionId, String reason, String operatorId, Set<String> roles) {
    }

    public record AdjustRequest(String assignmentId, String newWriterAccountId, String reason, String operatorId, Set<String> roles) {
    }
}
