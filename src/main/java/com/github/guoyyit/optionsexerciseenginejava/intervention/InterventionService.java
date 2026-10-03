package com.github.guoyyit.optionsexerciseenginejava.intervention;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;

public interface InterventionService {

    void revokeExercise(String positionId, String operator);

    void adjustAssignment(String referenceId, Assignment from, Assignment to, String operator);

    void checkWindowOpen();
}
