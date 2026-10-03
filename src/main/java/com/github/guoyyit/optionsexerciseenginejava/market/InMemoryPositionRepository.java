package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryPositionRepository implements PositionRepository {
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    @Override
    public java.util.List<Position> findAll() {
        return java.util.List.copyOf(positions.values());
    }

    @Override
    public List<Position> findByInstrument(String instrumentId) {
        return positions.values().stream()
                .filter(position -> position.instrumentId().equals(instrumentId))
                .toList();
    }

    @Override
    public List<Position> findByAccount(String accountId) {
        return positions.values().stream()
                .filter(position -> position.accountId().equals(accountId))
                .toList();
    }

    @Override
    public Optional<Position> find(String accountId, String instrumentId) {
        return Optional.ofNullable(positions.get(key(accountId, instrumentId)));
    }

    @Override
    public void replaceAll(Collection<Position> positions) {
        Map<String, Position> replacement = new ConcurrentHashMap<>();
        positions.forEach(position -> replacement.put(key(position.accountId(), position.instrumentId()), position));
        this.positions.clear();
        this.positions.putAll(replacement);
    }

    @Override
    public Position save(Position position) {
        positions.put(key(position.accountId(), position.instrumentId()), position);
        return position;
    }

    private static String key(String accountId, String instrumentId) {
        return accountId + "|" + instrumentId;
    }
}
