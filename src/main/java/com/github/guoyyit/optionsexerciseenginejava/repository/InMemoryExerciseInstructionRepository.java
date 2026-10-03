package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseInstruction;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryExerciseInstructionRepository implements ExerciseInstructionRepository {
    private final Map<String, ExerciseInstruction> instructions = new ConcurrentHashMap<>();

    @Override
    public Optional<ExerciseInstruction> findById(String id) {
        return Optional.ofNullable(instructions.get(id));
    }

    @Override
    public Optional<ExerciseInstruction> findByIdempotencyKey(String idempotencyKey) {
        return instructions.values().stream()
                .filter(instruction -> instruction.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public List<ExerciseInstruction> findByContractId(String contractId) {
        return instructions.values().stream()
                .filter(instruction -> instruction.contractId().equals(contractId))
                .toList();
    }

    @Override
    public List<ExerciseInstruction> findAll() {
        return List.copyOf(instructions.values());
    }

    @Override
    public void replaceAll(Collection<ExerciseInstruction> instructions) {
        Map<String, ExerciseInstruction> replacement = new ConcurrentHashMap<>();
        instructions.forEach(instruction -> replacement.put(instruction.id(), instruction));
        this.instructions.clear();
        this.instructions.putAll(replacement);
    }

    @Override
    public ExerciseInstruction save(ExerciseInstruction instruction) {
        instructions.put(instruction.id(), instruction);
        return instruction;
    }
}
