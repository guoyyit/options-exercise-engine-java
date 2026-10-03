package com.github.guoyyit.optionsexerciseenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Contract(
        String id,
        String underlying,
        OptionType optionType,
        ExerciseStyle exerciseStyle,
        BigDecimal strikePrice,
        LocalDate expiryDate
) {
}
