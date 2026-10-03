package com.github.guoyyit.optionsexerciseenginejava.intervention;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import com.github.guoyyit.optionsexerciseenginejava.exercise.ExerciseInstructionService;
import com.github.guoyyit.optionsexerciseenginejava.settlement.SettlementService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DefaultInterventionService implements InterventionService {

    private final PermissionService permissionService;
    private final AuditLog auditLog;
    private final SettlementService settlementService;
    private final ExerciseInstructionService exerciseInstructionService;
    private final EngineProperties properties;
    private final AtomicReference<Instant> windowStart = new AtomicReference<>();

    public DefaultInterventionService(PermissionService permissionService,
                                      AuditLog auditLog,
                                      SettlementService settlementService,
                                      ExerciseInstructionService exerciseInstructionService,
                                      EngineProperties properties) {
        this.permissionService = permissionService;
        this.auditLog = auditLog;
        this.settlementService = settlementService;
        this.exerciseInstructionService = exerciseInstructionService;
        this.properties = properties;
    }

    public void openWindow() {
        windowStart.set(Instant.now());
    }

    @Override
    public void revokeExercise(String positionId, String operator) {
        try {
            checkWindowOpen();
            requirePermission(operator, PermissionService.Permission.REVOKE_EXERCISE);
            if (settlementService.isPositionSettled(positionId)) {
                throw new IllegalStateException("position already settled, cannot revoke: " + positionId);
            }
            if (!exerciseInstructionService.isManualExerciseRequested(positionId)) {
                throw new IllegalStateException("no pending exercise instruction for position: " + positionId);
            }
            exerciseInstructionService.completeManualExercise(positionId);
            auditLog.record(operator, "REVOKE_EXERCISE", positionId,
                    "manual exercise instruction revoked", true, "revoked before settlement");
        } catch (RuntimeException e) {
            auditLog.record(operator, "REVOKE_EXERCISE", positionId,
                    "revocation rejected", false, e.getMessage());
            throw e;
        }
    }

    @Override
    public void adjustAssignment(String referenceId, Assignment from, Assignment to, String operator) {
        try {
            checkWindowOpen();
            requirePermission(operator, PermissionService.Permission.ADJUST_ASSIGNMENT);
            settlementService.applyAssignmentAdjustment(referenceId, from, to, operator);
            auditLog.record(operator, "ADJUST_ASSIGNMENT", referenceId,
                    from.shortPositionId() + "->" + to.shortPositionId() + " qty=" + to.quantity(),
                    true, "assignment adjusted");
        } catch (RuntimeException e) {
            auditLog.record(operator, "ADJUST_ASSIGNMENT", referenceId,
                    "adjustment rejected", false, e.getMessage());
            throw e;
        }
    }

    @Override
    public void checkWindowOpen() {
        long windowMs = properties.getInterventionWindowMs();
        if (windowMs <= 0) {
            return;
        }
        Instant start = windowStart.get();
        if (start == null) {
            throw new IllegalStateException("intervention window is not open");
        }
        if (Instant.now().isAfter(start.plusMillis(windowMs))) {
            throw new IllegalStateException("intervention window closed at " + start.plusMillis(windowMs));
        }
    }

    private void requirePermission(String operator, PermissionService.Permission permission) {
        if (!permissionService.hasPermission(operator, permission)) {
            throw new SecurityException("operator '" + operator + "' lacks permission " + permission);
        }
    }
}
