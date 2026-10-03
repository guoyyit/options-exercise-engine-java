package com.github.guoyyit.optionsexerciseenginejava.engine;

import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseDecision;

import java.util.List;

public interface ExpirationScanner {

    List<ExerciseDecision> scan(long expiryDate);
}
