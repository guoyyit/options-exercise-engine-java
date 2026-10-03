package com.github.guoyyit.optionsexerciseenginejava.domain;

import java.time.Instant;

public record Assignment(
        String id,
        String exerciseId,
        String contractId,
        String writerAccountId,
        long quantity,
        AssignmentStatus status,
        Instant createdAt
) {
}
