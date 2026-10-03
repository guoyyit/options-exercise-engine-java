package com.github.guoyyit.optionsexerciseenginejava.settlement;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;

import java.util.List;

public interface SettlementService {

    void settleLongExercise(String batchId, Position longPosition, long settlePrice, List<Assignment> assignments);

    void settleManualExercise(String positionId, Position longPosition, long settlePrice, List<Assignment> assignments);

    void applyAssignmentAdjustment(String referenceId, Assignment from, Assignment to, String operator);

    boolean isPositionSettled(String positionId);

    void recover();
}
