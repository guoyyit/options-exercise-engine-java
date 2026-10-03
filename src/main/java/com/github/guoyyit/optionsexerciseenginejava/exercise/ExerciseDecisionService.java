package com.github.guoyyit.optionsexerciseenginejava.exercise;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseDecision;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;

public interface ExerciseDecisionService {

    ExerciseDecision decide(Position longPosition, SettlementPrice settlementPrice, long threshold);

    long intrinsicValue(Position position, long settlementPrice);
}
