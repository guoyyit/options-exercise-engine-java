package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryPositionRepository implements PositionRepository {

    private final ConcurrentMap<String, Position> positions = new ConcurrentHashMap<>();

    public InMemoryPositionRepository() {
    }

    @Override
    public List<Position> findByExpiryDate(long expiryDate) {
        return positions.values().stream()
                .filter(p -> p.getExpiryDate() == expiryDate)
                .toList();
    }

    @Override
    public List<Position> findByContractCode(String contractCode) {
        return positions.values().stream()
                .filter(p -> p.getContractCode().equals(contractCode))
                .toList();
    }

    @Override
    public Optional<Position> findById(String positionId) {
        return Optional.ofNullable(positions.get(positionId));
    }

    @Override
    public List<Position> findAll() {
        return List.copyOf(positions.values());
    }

    @Override
    public void save(Position position) {
        positions.put(position.getId(), position);
    }

    @Override
    public synchronized long adjustQuantity(String positionId, long delta) {
        Position position = positions.get(positionId);
        if (position == null) {
            throw new IllegalArgumentException("position not found: " + positionId);
        }
        long updated = position.getQuantity() + delta;
        position.setQuantity(updated);
        return updated;
    }
}
