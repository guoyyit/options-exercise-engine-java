package com.github.guoyyit.optionsexerciseenginejava.exercise;

public interface ExerciseInstructionService {

    void requestExercise(String positionId, boolean override, String operator);

    boolean isManualExerciseRequested(String positionId);

    void completeManualExercise(String positionId);
}
