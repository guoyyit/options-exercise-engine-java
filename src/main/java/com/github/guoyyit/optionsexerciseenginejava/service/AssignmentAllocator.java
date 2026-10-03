package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;

import java.util.List;

public interface AssignmentAllocator {
    List<Assignment> allocate(Contract contract, List<Position> shortPositions, long exercisedQuantity, String exerciseId);
}
