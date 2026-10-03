package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.Position;

import java.util.List;
import java.util.Optional;

public interface PositionRepository {

    List<Position> findByExpiryDate(long expiryDate);

    List<Position> findByContractCode(String contractCode);

    Optional<Position> findById(String positionId);

    List<Position> findAll();

    void save(Position position);

    long adjustQuantity(String positionId, long delta);
}
