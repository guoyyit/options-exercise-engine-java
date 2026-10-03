package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseDecision;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import com.github.guoyyit.optionsexerciseenginejava.exercise.DefaultExerciseDecisionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseDecisionServiceTest {

    private final DefaultExerciseDecisionService service = new DefaultExerciseDecisionService();

    private Position longPosition(OptionType type, ExerciseStyle style, long strike) {
        return new Position("p1", "acct-1", "OPT", type, style, strike, 10L, 20260101L);
    }

    @Test
    void outOfTheMoneyIsAbandonedWithAuditableReason() {
        Position call = longPosition(OptionType.CALL, ExerciseStyle.EUROPEAN, 110L);
        ExerciseDecision decision = service.decide(call, new SettlementPrice("OPT", 100L), 0L);

        assertEquals(ExerciseAction.ABANDON, decision.action());
        assertTrue(decision.reason().contains("out-of-the-money"));
    }

    @Test
    void inTheMoneyBelowThresholdIsAbandoned() {
        Position call = longPosition(OptionType.CALL, ExerciseStyle.EUROPEAN, 95L);
        ExerciseDecision decision = service.decide(call, new SettlementPrice("OPT", 100L), 10L);

        assertEquals(ExerciseAction.ABANDON, decision.action());
        assertTrue(decision.reason().contains("below threshold"));
        assertEquals(5L, decision.intrinsicValue());
    }

    @Test
    void inTheMoneyAtOrAboveThresholdIsExercised() {
        Position put = longPosition(OptionType.PUT, ExerciseStyle.AMERICAN, 110L);
        ExerciseDecision decision = service.decide(put, new SettlementPrice("OPT", 100L), 10L);

        assertEquals(ExerciseAction.EXERCISE, decision.action());
        assertEquals(10L, decision.intrinsicValue());
    }

    @Test
    void zeroThresholdExercisesAnyInTheMoneyPosition() {
        Position put = longPosition(OptionType.PUT, ExerciseStyle.EUROPEAN, 101L);
        ExerciseDecision decision = service.decide(put, new SettlementPrice("OPT", 100L), 0L);

        assertEquals(ExerciseAction.EXERCISE, decision.action());
    }
}
