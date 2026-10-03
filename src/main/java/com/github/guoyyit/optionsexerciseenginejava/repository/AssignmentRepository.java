package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssignmentRepository {
    Optional<Assignment> findById(String id);

    List<Assignment> findByExerciseId(String exerciseId);

    List<Assignment> findByContractId(String contractId);

    List<Assignment> findAll();

    void replaceAll(Collection<Assignment> assignments);

    Assignment save(Assignment assignment);
}
