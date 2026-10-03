package com.github.guoyyit.optionsexerciseenginejava.assignment;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;

import java.util.List;

public interface AssignmentStrategy {

    String name();

    List<Assignment> allocate(String contractCode, long exercisedQuantity, List<Position> shortPositions, long batchNonce);
}
