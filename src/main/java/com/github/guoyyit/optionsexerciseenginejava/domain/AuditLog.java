package com.github.guoyyit.optionsexerciseenginejava.domain;

import java.time.Instant;

public record AuditLog(
        String id,
        AuditAction action,
        String operator,
        String targetId,
        String detail,
        Instant timestamp
) {
}
