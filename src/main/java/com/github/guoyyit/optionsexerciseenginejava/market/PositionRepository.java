package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.Position;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PositionRepository {
    java.util.List<Position> findAll();

    List<Position> findByInstrument(String instrumentId);

    List<Position> findByAccount(String accountId);

    Optional<Position> find(String accountId, String instrumentId);

    void replaceAll(Collection<Position> positions);

    Position save(Position position);
}
