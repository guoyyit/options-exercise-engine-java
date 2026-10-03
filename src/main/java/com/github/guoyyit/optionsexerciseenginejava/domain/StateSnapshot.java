package com.github.guoyyit.optionsexerciseenginejava.domain;

import java.util.List;

public record StateSnapshot(
        List<Contract> contracts,
        List<Position> positions,
        List<SettlementPrice> prices,
        List<ExerciseInstruction> exercises,
        List<Assignment> assignments,
        List<AuditLog> auditLogs
) {
    public StateSnapshot {
        contracts = List.copyOf(contracts);
        positions = List.copyOf(positions);
        prices = List.copyOf(prices);
        exercises = List.copyOf(exercises);
        assignments = List.copyOf(assignments);
        auditLogs = List.copyOf(auditLogs);
    }

    public static StateSnapshot empty() {
        return new StateSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
