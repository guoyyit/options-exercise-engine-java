package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.AuditLog;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryAuditLogRepository implements AuditLogRepository {
    private final List<AuditLog> auditLogs = new CopyOnWriteArrayList<>();

    @Override
    public AuditLog save(AuditLog auditLog) {
        auditLogs.add(auditLog);
        return auditLog;
    }

    @Override
    public List<AuditLog> findAll() {
        return List.copyOf(auditLogs);
    }

    @Override
    public synchronized void replaceAll(Collection<AuditLog> auditLogs) {
        this.auditLogs.clear();
        this.auditLogs.addAll(auditLogs);
    }
}
