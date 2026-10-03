package com.github.guoyyit.optionsexerciseenginejava.exercise;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultExerciseInstructionService implements ExerciseInstructionService {

    private final PositionRepository positionRepository;
    private final Map<String, String> pendingByPosition = new ConcurrentHashMap<>();

    public DefaultExerciseInstructionService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    @Override
    public void requestExercise(String positionId, boolean override, String operator) {
        Position position = positionRepository.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("position not found: " + positionId));
        if (!position.isLong()) {
            throw new IllegalArgumentException("only long positions can be exercised: " + positionId);
        }
        if (!override && position.getExerciseStyle() == ExerciseStyle.EUROPEAN) {
            throw new IllegalStateException(
                    "european options cannot be instructed before expiry: " + positionId);
        }
        pendingByPosition.put(positionId, operator == null ? "anonymous" : operator);
    }

    @Override
    public boolean isManualExerciseRequested(String positionId) {
        return pendingByPosition.containsKey(positionId);
    }

    @Override
    public void completeManualExercise(String positionId) {
        pendingByPosition.remove(positionId);
    }

    public void revoke(String positionId) {
        pendingByPosition.remove(positionId);
    }
}
