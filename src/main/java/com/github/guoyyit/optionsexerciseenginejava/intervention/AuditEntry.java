package com.github.guoyyit.optionsexerciseenginejava.intervention;

import java.time.Instant;

public class AuditEntry {
    private String id;
    private Instant timestamp;
    private String operator;
    private String action;
    private String target;
    private String detail;
    private boolean allowed;
    private String reason;

    public AuditEntry() {
    }

    public AuditEntry(String id, Instant timestamp, String operator, String action, String target,
                      String detail, boolean allowed, String reason) {
        this.id = id;
        this.timestamp = timestamp;
        this.operator = operator;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.allowed = allowed;
        this.reason = reason;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
