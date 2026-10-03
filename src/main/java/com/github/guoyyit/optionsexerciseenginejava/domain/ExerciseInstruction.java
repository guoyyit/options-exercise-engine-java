package com.github.guoyyit.optionsexerciseenginejava.domain;

import java.time.Instant;

public record ExerciseInstruction(
        String id,
        String contractId,
        String accountId,
        long quantity,
        ExerciseStyle exerciseStyle,
        ExerciseStatus status,
        String source,
        String reason,
        String idempotencyKey,
        Instant createdAt
) {
}
