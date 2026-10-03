package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryAssignmentRepository implements AssignmentRepository {
    private final Map<String, Assignment> assignments = new ConcurrentHashMap<>();

    @Override
    public Optional<Assignment> findById(String id) {
        return Optional.ofNullable(assignments.get(id));
    }

    @Override
    public List<Assignment> findByExerciseId(String exerciseId) {
        return assignments.values().stream()
                .filter(assignment -> assignment.exerciseId().equals(exerciseId))
                .toList();
    }

    @Override
    public List<Assignment> findByContractId(String contractId) {
        return assignments.values().stream()
                .filter(assignment -> assignment.contractId().equals(contractId))
                .toList();
    }

    @Override
    public List<Assignment> findAll() {
        return List.copyOf(assignments.values());
    }

    @Override
    public void replaceAll(Collection<Assignment> assignments) {
        Map<String, Assignment> replacement = new ConcurrentHashMap<>();
        assignments.forEach(assignment -> replacement.put(assignment.id(), assignment));
        this.assignments.clear();
        this.assignments.putAll(replacement);
    }

    @Override
    public Assignment save(Assignment assignment) {
        assignments.put(assignment.id(), assignment);
        return assignment;
    }
}
