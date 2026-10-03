package com.github.guoyyit.optionsexerciseenginejava.exercise;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseAction;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseDecision;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import org.springframework.stereotype.Service;

@Service
public class DefaultExerciseDecisionService implements ExerciseDecisionService {

    @Override
    public ExerciseDecision decide(Position longPosition, SettlementPrice settlementPrice, long threshold) {
        long price = settlementPrice.getPrice();
        long intrinsic = intrinsicValue(longPosition, price);
        if (intrinsic <= 0) {
            return ExerciseDecision.abandon(longPosition, price, intrinsic, false,
                    "out-of-the-money: intrinsic=" + intrinsic);
        }
        if (intrinsic < threshold) {
            return ExerciseDecision.abandon(longPosition, price, intrinsic, false,
                    "in-the-money but below threshold: intrinsic=" + intrinsic + ", threshold=" + threshold);
        }
        return ExerciseDecision.exercise(longPosition, price, intrinsic, false,
                "in-the-money: intrinsic=" + intrinsic + ", threshold=" + threshold);
    }

    public long intrinsicValue(Position position, long settlementPrice) {
        if (position.getOptionType() == OptionType.CALL) {
            return Math.max(0L, settlementPrice - position.getStrike());
        }
        return Math.max(0L, position.getStrike() - settlementPrice);
    }

    public boolean isInTheMoney(Position position, long settlementPrice) {
        return intrinsicValue(position, settlementPrice) > 0;
    }

    public ExerciseAction classify(Position position, long settlementPrice, long threshold) {
        long intrinsic = intrinsicValue(position, settlementPrice);
        if (intrinsic <= 0 || intrinsic < threshold) {
            return ExerciseAction.ABANDON;
        }
        return ExerciseAction.EXERCISE;
    }
}
