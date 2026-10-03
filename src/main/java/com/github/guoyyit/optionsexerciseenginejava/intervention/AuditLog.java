package com.github.guoyyit.optionsexerciseenginejava.intervention;

import java.util.List;

public interface AuditLog {

    AuditEntry record(String operator, String action, String target, String detail, boolean allowed, String reason);

    List<AuditEntry> findByTarget(String target);

    List<AuditEntry> findAll();
}
