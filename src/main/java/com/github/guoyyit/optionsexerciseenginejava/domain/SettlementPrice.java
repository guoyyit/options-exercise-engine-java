package com.github.guoyyit.optionsexerciseenginejava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementPrice(String contractId, BigDecimal price, LocalDate valuationDate) {
}
