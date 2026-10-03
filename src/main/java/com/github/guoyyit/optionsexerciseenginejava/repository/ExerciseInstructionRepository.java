package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExerciseInstructionRepository {
    Optional<ExerciseInstruction> findById(String id);

    Optional<ExerciseInstruction> findByIdempotencyKey(String idempotencyKey);

    List<ExerciseInstruction> findByContractId(String contractId);

    List<ExerciseInstruction> findAll();

    void replaceAll(Collection<ExerciseInstruction> instructions);

    ExerciseInstruction save(ExerciseInstruction instruction);
}
