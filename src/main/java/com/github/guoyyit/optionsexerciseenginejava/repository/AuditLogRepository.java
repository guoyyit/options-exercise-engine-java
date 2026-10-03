package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.AuditLog;

import java.util.Collection;
import java.util.List;

public interface AuditLogRepository {
    AuditLog save(AuditLog auditLog);

    List<AuditLog> findAll();

    void replaceAll(Collection<AuditLog> auditLogs);
}
