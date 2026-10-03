package com.github.guoyyit.optionsexerciseenginejava.engine;

public interface ExpirationEngine {

    EngineRunResult processExpiration(long expiryDate);

    void cancel();

    record EngineRunResult(long expiryDate, int scanned, int exercised, int abandoned,
                           int assigned, boolean cancelled, boolean timedOut) {
    }
}
