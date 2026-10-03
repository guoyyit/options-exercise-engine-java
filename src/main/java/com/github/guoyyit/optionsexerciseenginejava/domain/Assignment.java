package com.github.guoyyit.optionsexerciseenginejava.domain;

public record Assignment(String shortPositionId,
                         String accountId,
                         String contractCode,
                         long quantity) {

    public Assignment {
        if (quantity <= 0) {
            throw new IllegalArgumentException("assignment quantity must be positive: " + quantity);
        }
    }
}
