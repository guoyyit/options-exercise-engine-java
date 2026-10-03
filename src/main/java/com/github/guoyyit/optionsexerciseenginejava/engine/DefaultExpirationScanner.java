package com.github.guoyyit.optionsexerciseenginejava.engine;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseDecision;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import com.github.guoyyit.optionsexerciseenginejava.exercise.ExerciseDecisionService;
import com.github.guoyyit.optionsexerciseenginejava.exercise.ExerciseInstructionService;
import com.github.guoyyit.optionsexerciseenginejava.market.MarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class DefaultExpirationScanner implements ExpirationScanner {

    private final PositionRepository positionRepository;
    private final MarketDataProvider marketDataProvider;
    private final ExerciseDecisionService exerciseDecisionService;
    private final ExerciseInstructionService exerciseInstructionService;
    private final EngineProperties properties;

    public DefaultExpirationScanner(PositionRepository positionRepository,
                                    MarketDataProvider marketDataProvider,
                                    ExerciseDecisionService exerciseDecisionService,
                                    ExerciseInstructionService exerciseInstructionService,
                                    EngineProperties properties) {
        this.positionRepository = positionRepository;
        this.marketDataProvider = marketDataProvider;
        this.exerciseDecisionService = exerciseDecisionService;
        this.exerciseInstructionService = exerciseInstructionService;
        this.properties = properties;
    }

    @Override
    public List<ExerciseDecision> scan(long expiryDate) {
        List<Position> longs = positionRepository.findByExpiryDate(expiryDate).stream()
                .filter(Position::isLong)
                .sorted(Comparator.comparing(Position::getId))
                .toList();
        List<ExerciseDecision> decisions = new ArrayList<>();
        for (Position position : longs) {
            decisions.add(decideOne(position));
        }
        return List.copyOf(decisions);
    }

    private ExerciseDecision decideOne(Position position) {
        boolean manual = exerciseInstructionService.isManualExerciseRequested(position.getId());
        Optional<SettlementPrice> priceOpt = marketDataProvider.settlementPrice(position.getContractCode());
        if (priceOpt.isEmpty()) {
            return ExerciseDecision.abandon(position, 0L, 0L, manual,
                    "settlement price unavailable, conservative abandon");
        }
        SettlementPrice price = priceOpt.get();
        if (manual) {
            long intrinsic = exerciseDecisionService.intrinsicValue(position, price.getPrice());
            return ExerciseDecision.exercise(position, price.getPrice(), intrinsic, true,
                    "manual exercise instruction");
        }
        return exerciseDecisionService.decide(position, price, properties.getExerciseThreshold());
    }
}
